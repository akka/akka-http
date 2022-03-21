/*
 * Copyright (C) 2009-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.NotUsed
import akka.annotation.InternalApi
import akka.event.LoggingAdapter
import akka.http.impl.engine.{ HttpConnectionIdleTimeoutBidi, HttpIdleTimeoutException }
import akka.http.impl.engine.http2.FrameEvent._
import akka.http.impl.engine.http2.client.ResponseParsing
import akka.http.impl.engine.http2.framing.{ FrameRenderer, Http2FrameParsing }
import akka.http.impl.engine.http2.hpack.{ HeaderCompression, HeaderDecompression }
import akka.http.impl.engine.parsing.HttpHeaderParser
import akka.http.impl.engine.rendering.DateHeaderRendering
import akka.http.impl.engine.server.ServerTerminator
import akka.http.impl.util.LogByteStringTools.logTLSBidiBySetting
import akka.http.impl.util.StreamUtils
import akka.http.scaladsl.model._
import akka.http.scaladsl.settings.{ ClientConnectionSettings, Http2ClientSettings, Http2ServerSettings, ParserSettings, ServerSettings }
import akka.stream.{ BidiShape, Graph, StreamTcpException }
import akka.stream.TLSProtocol._
import akka.stream.scaladsl.{ BidiFlow, Flow, Keep, Source }
import akka.util.{ ByteString, OptionVal }

import scala.concurrent.duration.{ Duration, FiniteDuration }
import scala.concurrent.{ ExecutionContext, Future }
import scala.collection.immutable
import scala.util.control.NonFatal

/**
 * Represents one direction of an Http2 substream.
 */
@InternalApi
private[http2] case class Http2SubStream(
  initialHeaders: ParsedHeadersFrame,
  // outgoing response trailing headers can either be passed in eagerly via an attribute
  // or streaming as the LastChunk of a chunked data stream
  trailingHeaders:       OptionVal[ParsedHeadersFrame],
  data:                  Either[ByteString, Source[Any /* ByteString | HttpEntity.ChunkStreamPart */ , Any]],
  correlationAttributes: Map[AttributeKey[_], _]
) {
  def streamId: Int = initialHeaders.streamId
  def hasEntity: Boolean = !initialHeaders.endStream

  def withCorrelationAttributes(newAttributes: Map[AttributeKey[_], _]): Http2SubStream =
    copy(correlationAttributes = newAttributes)

  /**
   * Create the request entity (when we're the server) or response entity (when we're the client) for this substream
   */
  def createEntity(contentLength: Long, contentTypeOption: OptionVal[ContentType]): RequestEntity = {
    def contentType: ContentType = contentTypeOption.getOrElse(ContentTypes.`application/octet-stream`)

    data match {
      case Right(data) =>
        if (data == Source.empty || contentLength == 0 || !hasEntity) {
          if (contentTypeOption.isEmpty) HttpEntity.Empty
          else HttpEntity.Strict(contentType, ByteString.empty)
        } else {
          val chunkSource: Source[HttpEntity.ChunkStreamPart, Any] = data.map {
            case b: ByteString                 => HttpEntity.Chunk(b)
            case p: HttpEntity.ChunkStreamPart => p
            case x                             => throw new IllegalStateException(s"Only ByteString or ChunkStreamPart expected but got $x")
          }
          HttpEntity.Chunked(contentType, chunkSource)
        }
      case Left(dataBytes) =>
        if (dataBytes.isEmpty && contentTypeOption.isEmpty) HttpEntity.Empty
        else HttpEntity.Strict(contentType, dataBytes)
    }
  }
}
@InternalApi
private[http2] object Http2SubStream {
  def apply(entity: HttpEntity, headers: ParsedHeadersFrame, trailingHeaders: OptionVal[ParsedHeadersFrame], correlationAttributes: Map[AttributeKey[_], _] = Map.empty): Http2SubStream = {
    val data =
      entity match {
        case HttpEntity.Chunked(_, chunks) => Right(chunks)
        case HttpEntity.Strict(_, data)    => Left(data)
        case x                             => Right(x.dataBytes)
      }
    Http2SubStream(headers, trailingHeaders, data, correlationAttributes)
  }
}

/** INTERNAL API */
@InternalApi
private[http] object Http2Blueprint {

  def serverStackTls(settings: ServerSettings, log: LoggingAdapter, telemetry: TelemetrySpi, dateHeaderRendering: DateHeaderRendering): BidiFlow[HttpResponse, SslTlsOutbound, SslTlsInbound, HttpRequest, ServerTerminator] =
    serverStack(settings, log, telemetry = telemetry, dateHeaderRendering = dateHeaderRendering) atop
      unwrapTls atop
      logTLSBidiBySetting("server-plain-text", settings.logUnencryptedNetworkBytes)

  // format: OFF
  def serverStack(
      settings: ServerSettings,
      log: LoggingAdapter,
      initialDemuxerSettings: immutable.Seq[Setting] = Nil,
      upgraded: Boolean = false,
      telemetry: TelemetrySpi,
    dateHeaderRendering: DateHeaderRendering): BidiFlow[HttpResponse, ByteString, ByteString, HttpRequest, ServerTerminator] = {
    val masterHttpHeaderParser = HttpHeaderParser(settings.parserSettings, log) // FIXME: reuse for framing
    telemetry.serverConnection atop
      httpLayer(settings, log, dateHeaderRendering) atopKeepRight
      serverDemux(settings.http2Settings, initialDemuxerSettings, upgraded) atop
      FrameLogger.logFramesIfEnabled(settings.http2Settings.logFrames) atop // enable for debugging
      hpackCoding(masterHttpHeaderParser, settings.parserSettings) atop
      framing(log) atop
      errorHandling(log) atop
      idleTimeoutIfConfigured(settings.idleTimeout)
  }

  // LogByteStringTools.logToStringBidi("framing") atop // enable for debugging
  // format: ON

  def clientStack(settings: ClientConnectionSettings, log: LoggingAdapter, telemetry: TelemetrySpi): BidiFlow[HttpRequest, ByteString, ByteString, HttpResponse, NotUsed] = {
    // This is master header parser, every other usage should do .createShallowCopy()
    // HttpHeaderParser is not thread safe and should not be called concurrently,
    // the internal trie, however, has built-in protection and will do copy-on-write
    val masterHttpHeaderParser = HttpHeaderParser(settings.parserSettings, log)
    telemetry.client atop
      httpLayerClient(masterHttpHeaderParser, settings, log) atop
      clientDemux(settings.http2Settings, masterHttpHeaderParser) atop
      FrameLogger.logFramesIfEnabled(settings.http2Settings.logFrames) atop // enable for debugging
      hpackCoding(masterHttpHeaderParser, settings.parserSettings) atop
      framingClient(log) atop
      errorHandling(log) atop
      idleTimeoutIfConfigured(settings.idleTimeout)
  }

  def httpLayerClient(masterHttpHeaderParser: HttpHeaderParser, settings: ClientConnectionSettings, log: LoggingAdapter): BidiFlow[HttpRequest, Http2SubStream, Http2SubStream, HttpResponse, NotUsed] =
    BidiFlow.fromFlows(
      Flow[HttpRequest].statefulMapConcat { () =>
        val renderer = new RequestRendering(settings, log)
        request => renderer(request) :: Nil
      },
      StreamUtils.statefulAttrsMap[Http2SubStream, HttpResponse] { attrs =>
        val headerParser = masterHttpHeaderParser.createShallowCopy()
        stream => ResponseParsing.parseResponse(headerParser, settings.parserSettings, attrs)(stream)
      }
    )

  def idleTimeoutIfConfigured(timeout: Duration): BidiFlow[ByteString, ByteString, ByteString, ByteString, NotUsed] =
    timeout match {
      case f: FiniteDuration => HttpConnectionIdleTimeoutBidi(f, None)
      case _                 => BidiFlow.identity[ByteString, ByteString]
    }

  def errorHandling(log: LoggingAdapter): BidiFlow[ByteString, ByteString, ByteString, ByteString, NotUsed] =
    BidiFlow.fromFlows(
      StreamUtils.encodeErrorAndComplete {
        case ex: Http2Compliance.Http2ProtocolException =>
          // protocol errors are most likely provoked by peer, so we don't log them noisily
          if (log.isDebugEnabled) log.debug(s"HTTP2 connection failed with error [${ex.getMessage}]. Sending ${ex.errorCode} and closing connection.")
          FrameRenderer.render(GoAwayFrame(0, ex.errorCode))
        case ex: StreamTcpException => throw ex // TCP connection is probably broken: just forward exception
        case ex: HttpIdleTimeoutException =>
          // idle timeout stage is propagating this error but since it is already coming back we just propagate without logging
          throw ex
        case NonFatal(ex) =>
          log.error(s"HTTP2 connection failed with error [${ex.getMessage}]. Sending INTERNAL_ERROR and closing connection.")
          FrameRenderer.render(GoAwayFrame(0, Http2Protocol.ErrorCode.INTERNAL_ERROR))
      },
      Flow[ByteString]
    )

  def framing(log: LoggingAdapter): BidiFlow[FrameEvent, ByteString, ByteString, FrameEvent, NotUsed] =
    BidiFlow.fromFlows(
      Flow[FrameEvent].map(FrameRenderer.render),
      Flow[ByteString].via(new Http2FrameParsing(shouldReadPreface = true, log)))

  def framingClient(log: LoggingAdapter): BidiFlow[FrameEvent, ByteString, ByteString, FrameEvent, NotUsed] =
    BidiFlow.fromFlows(
      Flow[FrameEvent].map(FrameRenderer.render).prepend(Source.single(Http2Protocol.ClientConnectionPreface)),
      Flow[ByteString].via(new Http2FrameParsing(shouldReadPreface = false, log)))

  /**
   * Runs hpack encoding and decoding. Incoming frames that are processed are HEADERS and CONTINUATION.
   * Outgoing frame is ParsedHeadersFrame.
   * Other frames are propagated unchanged.
   *
   * TODO: introduce another FrameEvent type that exclude HeadersFrame and ContinuationFrame from
   * reaching the higher-level.
   */
  def hpackCoding(masterHttpHeaderParser: HttpHeaderParser, parserSettings: ParserSettings): BidiFlow[FrameEvent, FrameEvent, FrameEvent, FrameEvent, NotUsed] =
    BidiFlow.fromFlows(
      Flow[FrameEvent].via(HeaderCompression),
      Flow[FrameEvent].via(new HeaderDecompression(masterHttpHeaderParser, parserSettings))
    )

  /**
   * Creates substreams for every stream and manages stream state machines
   * and handles priorization (TODO: later)
   */
  def serverDemux(settings: Http2ServerSettings, initialDemuxerSettings: immutable.Seq[Setting], upgraded: Boolean): BidiFlow[Http2SubStream, FrameEvent, FrameEvent, Http2SubStream, ServerTerminator] =
    BidiFlow.fromGraph(new Http2ServerDemux(settings, initialDemuxerSettings, upgraded))

  /**
   * Creates substreams for every stream and manages stream state machines
   * and handles priorization (TODO: later)
   */
  def clientDemux(settings: Http2ClientSettings, masterHttpHeaderParser: HttpHeaderParser): BidiFlow[Http2SubStream, FrameEvent, FrameEvent, Http2SubStream, ServerTerminator] =
    BidiFlow.fromGraph(new Http2ClientDemux(settings, masterHttpHeaderParser))

  /**
   * Translation between substream frames and Http messages (both directions)
   *
   * To make use of parallelism requests and responses need to be associated (other than by ordering), suggestion
   * is to add a special (virtual) header containing the streamId (or any other kind of token) is added to the HttRequest
   * that must be reproduced in an HttpResponse. This can be done automatically for the `bind`` API but for
   * `bindFlow` the user needs to take of this manually.
   */
  def httpLayer(settings: ServerSettings, log: LoggingAdapter, dateHeaderRendering: DateHeaderRendering): BidiFlow[HttpResponse, Http2SubStream, Http2SubStream, HttpRequest, NotUsed] = {
    val parserSettings = settings.parserSettings
    // This is master header parser, every other usage should do .createShallowCopy()
    // HttpHeaderParser is not thread safe and should not be called concurrently,
    // the internal trie, however, has built-in protection and will do copy-on-write
    val masterHttpHeaderParser = HttpHeaderParser(parserSettings, log)
    BidiFlow.fromFlows(
      Flow[HttpResponse].map(new ResponseRendering(settings, log, dateHeaderRendering)),
      Flow[Http2SubStream].via(StreamUtils.statefulAttrsMap { attrs =>
        val headerParser = masterHttpHeaderParser.createShallowCopy()
        RequestParsing.parseRequest(headerParser, settings, attrs)
      }))
  }

  /**
   * Returns a flow that handles `parallelism` requests in parallel, automatically keeping track of the
   * Http2StreamIdHeader between request and responses.
   */
  def handleWithStreamIdHeader(parallelism: Int)(handler: HttpRequest => Future[HttpResponse])(implicit ec: ExecutionContext): Flow[HttpRequest, HttpResponse, NotUsed] =
    Flow[HttpRequest]
      .mapAsyncUnordered(parallelism) { req =>
        // The handler itself may do significant work so make sure to schedule it separately. This is especially important for HTTP/2 where it is expected that
        // multiple requests are handled concurrently on the same connection. The complete stream including `mapAsyncUnordered` shares one GraphInterpreter, so
        // that this extra indirection will guard the GraphInterpreter from being starved by user code.
        Future {
          val response = handler(req)

          req.attribute(Http2.streamId) match {
            case Some(streamIdHeader) => response.map(_.addAttribute(Http2.streamId, streamIdHeader)) // add stream id attribute when request had it
            case None                 => response
          }
        }.flatten
      }

  private[http2] def logParsingError(info: ErrorInfo, log: LoggingAdapter,
                                     setting: ParserSettings.ErrorLoggingVerbosity): Unit =
    setting match {
      case ParserSettings.ErrorLoggingVerbosity.Off    => // nothing to do
      case ParserSettings.ErrorLoggingVerbosity.Simple => log.warning(info.summary)
      case ParserSettings.ErrorLoggingVerbosity.Full   => log.warning(info.formatPretty)
    }

  private[http] val unwrapTls: BidiFlow[ByteString, SslTlsOutbound, SslTlsInbound, ByteString, NotUsed] =
    BidiFlow.fromFlows(Flow[ByteString].map(SendBytes(_)), Flow[SslTlsInbound].collect {
      case SessionBytes(_, bytes) => bytes
    })

  implicit class BidiFlowExt[I1, O1, I2, O2, Mat](bidi: BidiFlow[I1, O1, I2, O2, Mat]) {
    def atopKeepRight[OO1, II2, Mat2](other: Graph[BidiShape[O1, OO1, II2, I2], Mat2]): BidiFlow[I1, OO1, II2, O2, Mat2] =
      bidi.atopMat(other)(Keep.right)
  }
}
