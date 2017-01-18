/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.rendering

import akka.NotUsed
import akka.http.impl.engine.ws.{ FrameEvent, UpgradeToWebSocketResponseHeader }
import akka.http.scaladsl.model.ws.Message
import akka.stream.{ Attributes, FlowShape, Graph, Inlet, Outlet }

import scala.annotation.tailrec
import scala.collection.immutable
import akka.event.LoggingAdapter
import akka.util.{ ByteString, OptionVal }
import akka.stream.scaladsl.{ Flow, Source }
import akka.stream.stage._
import akka.http.scaladsl.model._
import akka.http.impl.util._
import RenderSupport._
import HttpProtocols._
import headers._

/**
 * INTERNAL API
 */
private[http] class HttpResponseRendererFactory(
  serverHeader:           Option[headers.Server],
  responseHeaderSizeHint: Int,
  log:                    LoggingAdapter) {

  private val renderDefaultServerHeader: Rendering ⇒ Unit =
    serverHeader match {
      case Some(h) ⇒
        val bytes = (new ByteArrayRendering(32) ~~ h ~~ CrLf).get
        _ ~~ bytes
      case None ⇒ _ ⇒ ()
    }

  // as an optimization we cache the Date header of the last second here
  @volatile private[this] var cachedDateHeader: (Long, Array[Byte]) = (0L, null)

  private def dateHeader: Array[Byte] = {
    var (cachedSeconds, cachedBytes) = cachedDateHeader
    val now = currentTimeMillis()
    if (now / 1000 > cachedSeconds) {
      cachedSeconds = now / 1000
      val r = new ByteArrayRendering(48)
      DateTime(now).renderRfc1123DateTimeString(r ~~ headers.Date) ~~ CrLf
      cachedBytes = r.get
      cachedDateHeader = cachedSeconds → cachedBytes
    }
    cachedBytes
  }

  // split out so we can stabilize by overriding in tests
  protected def currentTimeMillis(): Long = System.currentTimeMillis()

  def renderer: Flow[ResponseRenderingContext, ResponseRenderingOutput, NotUsed] = Flow.fromGraph(HttpResponseRenderer)

  object HttpResponseRenderer extends GraphStage[FlowShape[ResponseRenderingContext, ResponseRenderingOutput]] {
    val in = Inlet[ResponseRenderingContext]("HttpResponseRenderer.in")
    val out = Outlet[ResponseRenderingOutput]("HttpResponseRenderer.out")
    val shape: FlowShape[ResponseRenderingContext, ResponseRenderingOutput] = FlowShape(in, out)

    def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
      new GraphStageLogic(shape) {
        var closeMode: CloseMode = DontClose // signals what to do after the current response
        def close: Boolean = closeMode != DontClose
        def closeIf(cond: Boolean): Unit = if (cond) closeMode = CloseConnection
        var transferring = false

        setHandler(in, new InHandler {
          override def onPush(): Unit =
            render(grab(in)) match {
              case Strict(outElement) ⇒
                push(out, outElement)
                if (close) completeStage()
              case Streamed(outStream) ⇒ transfer(outStream)
            }

          override def onUpstreamFinish(): Unit =
            if (transferring) closeMode = CloseConnection
            else completeStage()
        })
        val waitForDemandHandler = new OutHandler {
          def onPull(): Unit = pull(in)
        }
        setHandler(out, waitForDemandHandler)
        def transfer(outStream: Source[ResponseRenderingOutput, Any]): Unit = {
          transferring = true
          val sinkIn = new SubSinkInlet[ResponseRenderingOutput]("RenderingSink")
          sinkIn.setHandler(new InHandler {
            override def onPush(): Unit = push(out, sinkIn.grab())
            override def onUpstreamFinish(): Unit =
              if (close) completeStage()
              else {
                transferring = false
                setHandler(out, waitForDemandHandler)
                if (isAvailable(out)) pull(in)
              }
          })
          setHandler(out, new OutHandler {
            override def onPull(): Unit = sinkIn.pull()
            override def onDownstreamFinish(): Unit = {
              completeStage()
              sinkIn.cancel()
            }
          })
          sinkIn.pull()
          outStream.runWith(sinkIn.sink)(interpreter.subFusingMaterializer)
        }

        def render(ctx: ResponseRenderingContext): StrictOrStreamed = {
          val r = new ByteStringRendering(responseHeaderSizeHint)

          import ctx.response._
          val noEntity = entity.isKnownEmpty || ctx.requestMethod == HttpMethods.HEAD

          val headerRenderer = new AbstractHeaderRenderer[ByteStringRendering] {
            override protected def render(h: HttpHeader, result: ByteStringRendering) = result ~~ h ~~ CrLf
            override protected def suppressed(h: HttpHeader, msg: String) = suppressionWarning(log, h, msg)
            override protected def suppressed(h: HttpHeader) = suppressionWarning(log, h)

            override protected def renderDefaultServerHeader(result: ByteStringRendering) = {
              HttpResponseRendererFactory.this.renderDefaultServerHeader(result)
            }

            override protected def renderDateHeader(result: ByteStringRendering) = {
              result ~~ dateHeader
            }

            override protected def headerRenderingComplete(
              rendering:                               ByteStringRendering,
              mustRenderTransferEncodingChunkedHeader: Boolean,
              alwaysClose:                             Boolean,
              connHeader:                              Connection,
              serverSeen:                              Boolean,
              transferEncodingSeen:                    Boolean,
              dateSeen:                                Boolean): Unit = {

              // Do we close the connection after this response?
              closeIf {
                // if we are prohibited to keep-alive by the spec
                alwaysClose ||
                  // if the client wants to close and we don't override
                  (ctx.closeRequested && ((connHeader eq null) || !connHeader.hasKeepAlive)) ||
                  // if the application wants to close explicitly
                  (protocol match {
                    case `HTTP/1.1` ⇒ (connHeader ne null) && connHeader.hasClose
                    case `HTTP/1.0` ⇒ if (connHeader eq null) ctx.requestProtocol == `HTTP/1.1` else !connHeader.hasKeepAlive
                  })
              }

              // Do we render an explicit Connection header?
              val renderConnectionHeader =
                protocol == `HTTP/1.0` && !close || protocol == `HTTP/1.1` && close || // if we don't follow the default behavior
                  close != ctx.closeRequested || // if we override the client's closing request
                  protocol != ctx.requestProtocol // if we reply with a mismatching protocol (let's be very explicit in this case)

              if (renderConnectionHeader)
                rendering ~~ Connection ~~ (if (close) CloseBytes else KeepAliveBytes) ~~ CrLf
              else if (connHeader != null && connHeader.hasUpgrade) {
                rendering ~~ connHeader ~~ CrLf
                HttpHeader.fastFind(classOf[UpgradeToWebSocketResponseHeader], headers) match {
                  case OptionVal.Some(header) ⇒ closeMode = SwitchToWebSocket(header.handler)
                  case _                      ⇒ // nothing to do here...
                }
              }
              if (mustRenderTransferEncodingChunkedHeader && !transferEncodingSeen)
                rendering ~~ `Transfer-Encoding` ~~ ChunkedBytes ~~ CrLf
            }
          }

          def renderStatusLine(): Unit =
            protocol match {
              case `HTTP/1.1` ⇒ if (status eq StatusCodes.OK) r ~~ DefaultStatusLineBytes else r ~~ StatusLineStartBytes ~~ status ~~ CrLf
              case `HTTP/1.0` ⇒ r ~~ protocol ~~ ' ' ~~ status ~~ CrLf
            }

          def mustRenderTransferEncodingChunkedHeader =
            entity.isChunked && (!entity.isKnownEmpty || ctx.requestMethod == HttpMethods.HEAD) && (ctx.requestProtocol == `HTTP/1.1`)

          def renderContentLengthHeader(contentLength: Long) =
            if (status.allowsEntity) r ~~ `Content-Length` ~~ contentLength ~~ CrLf else r

          def byteStrings(entityBytes: ⇒ Source[ByteString, Any]): Source[ResponseRenderingOutput, Any] =
            renderByteStrings(r, entityBytes, skipEntity = noEntity).map(ResponseRenderingOutput.HttpData)

          @tailrec def completeResponseRendering(entity: ResponseEntity): StrictOrStreamed =
            entity match {
              case HttpEntity.Strict(_, data) ⇒
                headerRenderer.renderHeaders(headers, r, mustRenderTransferEncodingChunkedHeader)
                renderEntityContentType(r, entity)
                renderContentLengthHeader(data.length) ~~ CrLf

                if (!noEntity) r ~~ data

                Strict {
                  closeMode match {
                    case SwitchToWebSocket(handler) ⇒ ResponseRenderingOutput.SwitchToWebSocket(r.get, handler)
                    case _                          ⇒ ResponseRenderingOutput.HttpData(r.get)
                  }
                }

              case HttpEntity.Default(_, contentLength, data) ⇒
                headerRenderer.renderHeaders(headers, r, mustRenderTransferEncodingChunkedHeader)
                renderEntityContentType(r, entity)
                renderContentLengthHeader(contentLength) ~~ CrLf
                Streamed(byteStrings(data.via(CheckContentLengthTransformer.flow(contentLength))))

              case HttpEntity.CloseDelimited(_, data) ⇒
                headerRenderer.renderHeaders(headers, r, mustRenderTransferEncodingChunkedHeader, alwaysClose = ctx.requestMethod != HttpMethods.HEAD)
                renderEntityContentType(r, entity) ~~ CrLf
                Streamed(byteStrings(data))

              case HttpEntity.Chunked(contentType, chunks) ⇒
                if (ctx.requestProtocol == `HTTP/1.0`)
                  completeResponseRendering(HttpEntity.CloseDelimited(contentType, chunks.map(_.data)))
                else {
                  headerRenderer.renderHeaders(headers, r, mustRenderTransferEncodingChunkedHeader)
                  renderEntityContentType(r, entity) ~~ CrLf
                  Streamed(byteStrings(chunks.via(ChunkTransformer.flow)))
                }
            }

          renderStatusLine()
          completeResponseRendering(entity)
        }
      }

    sealed trait StrictOrStreamed
    case class Strict(bytes: ResponseRenderingOutput) extends StrictOrStreamed
    case class Streamed(source: Source[ResponseRenderingOutput, Any]) extends StrictOrStreamed
  }

  sealed trait CloseMode
  case object DontClose extends CloseMode
  case object CloseConnection extends CloseMode
  case class SwitchToWebSocket(handler: Either[Graph[FlowShape[FrameEvent, FrameEvent], Any], Graph[FlowShape[Message, Message], Any]]) extends CloseMode
}

/**
 * INTERNAL API
 *
 * Shared base logic for rendering response headers with common logic for http 1 and 2
 *
 * @tparam T a mutable builder of some kind to build up the headers in
 */
private[http] abstract class AbstractHeaderRenderer[T] {

  protected def render(h: HttpHeader, builder: T)

  protected def suppressed(h: HttpHeader, msg: String): Unit
  protected def suppressed(h: HttpHeader): Unit

  protected def renderDefaultServerHeader(builder: T): Unit
  protected def renderDateHeader(builder: T): Unit

  // hook for protocol version specific auto headers/closing etc.
  protected def headerRenderingComplete(builder: T, mustRenderTransferEncodingChunkedHeader: Boolean, alwaysClose: Boolean, connHeader: Connection, serverSeen: Boolean, transferEncodingSeen: Boolean, dateSeen: Boolean): Unit

  def renderHeaders(headers: immutable.Seq[HttpHeader], builder: T, mustRenderTransferEncodingChunkedHeader: Boolean, alwaysClose: Boolean = false): Unit = {
    var connHeader: Connection = null
    var serverSeen: Boolean = false
    var transferEncodingSeen: Boolean = false
    var dateSeen: Boolean = false
    val indexedHeaders = headers.toIndexedSeq
    val size = indexedHeaders.size
    var idx = 0

    while (idx < size) {
      indexedHeaders(idx) match {
        case x: Server ⇒
          serverSeen = true
          render(x, builder)

        case x: Date ⇒
          dateSeen = true
          render(x, builder)

        case x: `Content-Length` ⇒
          suppressed(x, "explicit `Content-Length` header is not allowed. Use the appropriate HttpEntity subtype.")

        case x: `Content-Type` ⇒
          suppressed(x, "explicit `Content-Type` header is not allowed. Set `HttpResponse.entity.contentType` instead.")

        case x: `Transfer-Encoding` ⇒
          x.withChunkedPeeled match {
            case None ⇒
              suppressed(x)

            case Some(te) ⇒
              // if the user applied some custom transfer-encoding we need to keep the header
              transferEncodingSeen = true
              render(if (mustRenderTransferEncodingChunkedHeader) te.withChunked else te, builder)

          }

        case x: Connection ⇒
          connHeader = if (connHeader eq null) x else Connection(x.tokens ++ connHeader.tokens)

        case x: CustomHeader ⇒
          if (x.renderInResponses) render(x, builder)

        case x: RawHeader if (x is "content-type") || (x is "content-length") || (x is "transfer-encoding") ||
          (x is "date") || (x is "server") || (x is "connection") ⇒
          suppressed(x, "illegal RawHeader")

        case x ⇒
          if (x.renderInResponses) render(x, builder)
          else {
            suppressed(x, "HTTP header is not allowed in responses")
          }
      }
      idx += 1
    }

    if (!serverSeen) renderDefaultServerHeader(builder)
    if (!dateSeen) renderDateHeader(builder)

    headerRenderingComplete(builder, mustRenderTransferEncodingChunkedHeader, alwaysClose, connHeader, serverSeen, transferEncodingSeen, dateSeen)
  }

}

/**
 * INTERNAL API
 */
private[http] final case class ResponseRenderingContext(
  response:        HttpResponse,
  requestMethod:   HttpMethod   = HttpMethods.GET,
  requestProtocol: HttpProtocol = HttpProtocols.`HTTP/1.1`,
  closeRequested:  Boolean      = false)

/** INTERNAL API */
private[http] sealed trait ResponseRenderingOutput
/** INTERNAL API */
private[http] object ResponseRenderingOutput {
  private[http] case class HttpData(bytes: ByteString) extends ResponseRenderingOutput
  private[http] case class SwitchToWebSocket(httpResponseBytes: ByteString, handler: Either[Graph[FlowShape[FrameEvent, FrameEvent], Any], Graph[FlowShape[Message, Message], Any]]) extends ResponseRenderingOutput
}
