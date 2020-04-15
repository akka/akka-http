/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.rendering

import akka.NotUsed
import akka.stream.{ Attributes, FlowShape, Inlet, Outlet }

import scala.collection.immutable
import scala.annotation.tailrec
import akka.event.LoggingAdapter
import akka.util.{ ByteString, OptionVal }
import akka.stream.scaladsl.{ Flow, Source }
import akka.stream.stage._
import akka.http.scaladsl.model._
import akka.http.impl.util._
import RenderSupport._
import HttpProtocols._
import akka.annotation.InternalApi
import ResponseRenderingContext.CloseRequested
import akka.http.impl.engine.server.UpgradeToOtherProtocolResponseHeader
import akka.stream.scaladsl.Sink
import headers._

import scala.util.control.NonFatal

/**
 * INTERNAL API
 */
@InternalApi
private[http] class HttpResponseRendererFactory(
  serverHeader:           Option[headers.Server],
  responseHeaderSizeHint: Int,
  log:                    LoggingAdapter) {

  private val renderDefaultServerHeader: Rendering => Unit =
    serverHeader match {
      case Some(h) =>
        val bytes = (new ByteArrayRendering(32) ~~ h ~~ CrLf).get
        _ ~~ bytes
      case None => _ => ()
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
      cachedDateHeader = cachedSeconds -> cachedBytes
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
        var transferSink: Option[SubSinkInlet[ByteString]] = None
        def transferring: Boolean = transferSink.isDefined

        setHandler(in, new InHandler {
          override def onPush(): Unit =
            render(grab(in)) match {
              case Strict(outElement) =>
                push(out, outElement)
                if (close) completeStage()
              case HeadersAndStreamedEntity(headerData, outStream) =>
                try transfer(headerData, outStream)
                catch {
                  case NonFatal(e) =>
                    log.error(e, s"Rendering of response failed because response entity stream materialization failed with '${e.getMessage}'. Sending out 500 response instead.")
                    push(out, render(ResponseRenderingContext(HttpResponse(500, entity = StatusCodes.InternalServerError.defaultMessage))).asInstanceOf[Strict].bytes)
                }
            }

          override def onUpstreamFinish(): Unit =
            if (transferring) closeMode = CloseConnection
            else completeStage()

          override def onUpstreamFailure(ex: Throwable): Unit = {
            stopTransfer()
            failStage(ex)
          }
        })
        private val waitForDemandHandler = new OutHandler {
          def onPull(): Unit = if (!hasBeenPulled(in)) tryPull(in)
        }
        def stopTransfer(): Unit = {
          setHandler(out, waitForDemandHandler)
          if (isAvailable(out) && !hasBeenPulled(in)) tryPull(in)
          transferSink.foreach(_.cancel())
          transferSink = None
        }

        setHandler(out, waitForDemandHandler)
        def transfer(headerData: ByteString, outStream: Source[ByteString, Any]): Unit = {
          val sinkIn = new SubSinkInlet[ByteString]("RenderingSink")
          transferSink = Some(sinkIn)

          sinkIn.setHandler(new InHandler {
            override def onPush(): Unit = push(out, ResponseRenderingOutput.HttpData(sinkIn.grab()))
            override def onUpstreamFinish(): Unit =
              if (close) completeStage()
              else stopTransfer()
          })

          var headersSent = false
          def sendHeaders(): Unit = {
            push(out, ResponseRenderingOutput.HttpData(headerData))
            headersSent = true
          }
          setHandler(out, new OutHandler {
            override def onPull(): Unit =
              if (!headersSent) sendHeaders()
              else sinkIn.pull()
            override def onDownstreamFinish(): Unit = {
              completeStage()
              stopTransfer()
            }
          })

          try {
            outStream.runWith(sinkIn.sink)(interpreter.subFusingMaterializer)
            if (isAvailable(out)) sendHeaders()
          } catch {
            case NonFatal(e) =>
              stopTransfer()
              throw e
          }
        }

        def render(ctx: ResponseRenderingContext): StrictOrStreamed = {
          val r = new ByteArrayRendering(responseHeaderSizeHint)

          import ctx.response._
          val noEntity = entity.isKnownEmpty || ctx.requestMethod == HttpMethods.HEAD

          def renderStatusLine(): Unit =
            protocol match {
              case `HTTP/1.1` => if (status eq StatusCodes.OK) r ~~ DefaultStatusLineBytes else r ~~ StatusLineStartBytes ~~ status ~~ CrLf
              case `HTTP/1.0` => r ~~ protocol ~~ ' ' ~~ status ~~ CrLf
              case other      => throw new IllegalStateException(s"Unexpected protocol '$other'")
            }

          def render(h: HttpHeader) = r ~~ h ~~ CrLf

          def mustRenderTransferEncodingChunkedHeader =
            entity.isChunked && (!entity.isKnownEmpty || ctx.requestMethod == HttpMethods.HEAD) && (ctx.requestProtocol == `HTTP/1.1`)

          def renderHeaders(headers: immutable.Seq[HttpHeader], alwaysClose: Boolean = false): Unit = {
            var connHeader: Connection = null
            var serverSeen: Boolean = false
            var transferEncodingSeen: Boolean = false
            var dateSeen: Boolean = false

            val it = headers.iterator
            while (it.hasNext)
              it.next() match {
                case x: Server =>
                  render(x)
                  serverSeen = true

                case x: Date =>
                  render(x)
                  dateSeen = true

                case x: `Content-Length` =>
                  suppressionWarning(log, x, "explicit `Content-Length` header is not allowed. Use the appropriate HttpEntity subtype.")

                case x: `Content-Type` =>
                  suppressionWarning(log, x, "explicit `Content-Type` header is not allowed. Set `HttpResponse.entity.contentType` instead.")

                case x: `Transfer-Encoding` =>
                  x.withChunkedPeeled match {
                    case None =>
                      suppressionWarning(log, x)
                    case Some(te) =>
                      // if the user applied some custom transfer-encoding we need to keep the header
                      render(if (mustRenderTransferEncodingChunkedHeader) te.withChunked else te)
                      transferEncodingSeen = true
                  }

                case x: Connection =>
                  connHeader = if (connHeader eq null) x else Connection(x.tokens ++ connHeader.tokens)

                case x: CustomHeader =>
                  if (x.renderInResponses) render(x)

                case x: RawHeader if (x is "content-type") || (x is "content-length") || (x is "transfer-encoding") ||
                  (x is "date") || (x is "server") || (x is "connection") =>
                  suppressionWarning(log, x, "illegal RawHeader")

                case x =>
                  if (x.renderInResponses) render(x)
                  else log.warning("HTTP header '{}' is not allowed in responses", x)
              }

            if (!serverSeen) renderDefaultServerHeader(r)
            if (!dateSeen) r ~~ dateHeader

            // Do we close the connection after this response?
            closeIf {
              // if we are prohibited to keep-alive by the spec
              alwaysClose ||
                // if the controller asked for closing (error, early response, etc. overrides anything
                ctx.closeRequested.wasForced ||
                // if the client wants to close and the response doesn't override
                (ctx.closeRequested.shouldClose && ((connHeader eq null) || !connHeader.hasKeepAlive)) ||
                // if the application wants to close explicitly
                (protocol match {
                  case `HTTP/1.1` => (connHeader ne null) && connHeader.hasClose
                  case `HTTP/1.0` => if (connHeader eq null) ctx.requestProtocol == `HTTP/1.1` else !connHeader.hasKeepAlive
                  case other      => throw new IllegalStateException(s"Unexpected protocol '$other'")
                })
            }

            // Do we render an explicit Connection header?
            val renderConnectionHeader =
              protocol == `HTTP/1.0` && !close || protocol == `HTTP/1.1` && close || // if we don't follow the default behavior
                close != ctx.closeRequested.shouldClose || // if we override the client's closing request
                protocol != ctx.requestProtocol // if we reply with a mismatching protocol (let's be very explicit in this case)

            if (renderConnectionHeader)
              r ~~ Connection ~~ (if (close) CloseBytes else KeepAliveBytes) ~~ CrLf
            else if (connHeader != null && connHeader.hasUpgrade) {
              r ~~ connHeader ~~ CrLf
              HttpHeader.fastFind(classOf[UpgradeToOtherProtocolResponseHeader], headers) match {
                case OptionVal.Some(header) => closeMode = SwitchToOtherProtocol(header.handler)
                case _                      => // nothing to do here...
              }
            }
            if (mustRenderTransferEncodingChunkedHeader && !transferEncodingSeen)
              r ~~ `Transfer-Encoding` ~~ ChunkedBytes ~~ CrLf
          }

          def renderContentLengthHeader(contentLength: Long) =
            if (status.allowsEntity) r ~~ `Content-Length` ~~ contentLength ~~ CrLf else r

          def headersAndEntity(entityBytes: => Source[ByteString, Any]): StrictOrStreamed =
            if (noEntity) {
              entityBytes.runWith(Sink.cancelled)(subFusingMaterializer)
              Strict(ResponseRenderingOutput.HttpData(r.asByteString))
            } else {
              HeadersAndStreamedEntity(
                r.asByteString,
                entityBytes
              )
            }

          @tailrec def completeResponseRendering(entity: ResponseEntity): StrictOrStreamed =
            entity match {
              case HttpEntity.Strict(_, data) =>
                renderHeaders(headers)
                renderEntityContentType(r, entity)
                renderContentLengthHeader(data.length) ~~ CrLf

                val finalBytes = {
                  if (!noEntity)
                    if (data.size < r.remainingCapacity) (r ~~ data).asByteString
                    else r.asByteString ++ data
                  else
                    r.asByteString
                }

                Strict {
                  closeMode match {
                    case SwitchToOtherProtocol(handler) => ResponseRenderingOutput.SwitchToOtherProtocol(finalBytes, handler)
                    case _                              => ResponseRenderingOutput.HttpData(finalBytes)
                  }
                }

              case HttpEntity.Default(_, contentLength, data) =>
                renderHeaders(headers)
                renderEntityContentType(r, entity)
                renderContentLengthHeader(contentLength) ~~ CrLf
                headersAndEntity(data.via(CheckContentLengthTransformer.flow(contentLength)))

              case HttpEntity.CloseDelimited(_, data) =>
                renderHeaders(headers, alwaysClose = ctx.requestMethod != HttpMethods.HEAD)
                renderEntityContentType(r, entity) ~~ CrLf
                headersAndEntity(data)

              case HttpEntity.Chunked(contentType, chunks) =>
                if (ctx.requestProtocol == `HTTP/1.0`)
                  completeResponseRendering(HttpEntity.CloseDelimited(contentType, chunks.map(_.data)))
                else {
                  renderHeaders(headers)
                  renderEntityContentType(r, entity) ~~ CrLf
                  headersAndEntity(chunks.via(ChunkTransformer.flow))
                }
            }

          renderStatusLine()
          completeResponseRendering(entity)
        }
      }

    sealed trait StrictOrStreamed
    case class Strict(bytes: ResponseRenderingOutput) extends StrictOrStreamed
    case class HeadersAndStreamedEntity(headerBytes: ByteString, remainingData: Source[ByteString, Any]) extends StrictOrStreamed
  }

  sealed trait CloseMode
  case object DontClose extends CloseMode
  case object CloseConnection extends CloseMode
  case class SwitchToOtherProtocol(handler: Flow[ByteString, ByteString, Any]) extends CloseMode
}

/**
 * INTERNAL API
 */
@InternalApi
private[http] final case class ResponseRenderingContext(
  response:        HttpResponse,
  requestMethod:   HttpMethod     = HttpMethods.GET,
  requestProtocol: HttpProtocol   = HttpProtocols.`HTTP/1.1`,
  closeRequested:  CloseRequested = CloseRequested.Unspecified)

/**
 * INTERNAL API
 */
@InternalApi
private[http] object ResponseRenderingContext {
  sealed trait CloseRequested {
    def shouldClose: Boolean
    def wasForced: Boolean
  }
  object CloseRequested {
    case object Unspecified extends CloseRequested {
      override def shouldClose: Boolean = false
      override def wasForced: Boolean = false
    }
    case object RequestAskedForClosing extends CloseRequested {
      override def shouldClose: Boolean = true
      override def wasForced: Boolean = false
    }
    case object ForceClose extends CloseRequested {
      override def shouldClose: Boolean = true
      override def wasForced: Boolean = true
    }
  }
}

/** INTERNAL API */
@InternalApi
private[http] sealed trait ResponseRenderingOutput
/** INTERNAL API */
@InternalApi
private[http] object ResponseRenderingOutput {
  private[http] case class HttpData(bytes: ByteString) extends ResponseRenderingOutput
  private[http] case class SwitchToOtherProtocol(httpResponseBytes: ByteString, newHandler: Flow[ByteString, ByteString, Any]) extends ResponseRenderingOutput
}
