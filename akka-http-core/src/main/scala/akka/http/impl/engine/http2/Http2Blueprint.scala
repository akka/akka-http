/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.NotUsed
import akka.annotation.InternalApi
import akka.event.LoggingAdapter
import akka.http.impl.engine.HttpConnectionIdleTimeoutBidi
import akka.http.impl.engine.http2.FrameEvent._
import akka.http.impl.engine.http2.client.{ RequestRendering, ResponseParsing }
import akka.http.impl.engine.http2.framing.{ Http2FrameParsing, Http2FrameRendering }
import akka.http.impl.engine.http2.hpack.{ HeaderCompression, HeaderDecompression }
import akka.http.impl.engine.parsing.HttpHeaderParser
import akka.http.impl.util.LogByteStringTools.logTLSBidiBySetting
import akka.http.impl.util.StreamUtils
import akka.http.scaladsl.model.HttpEntity.Chunk
import akka.http.scaladsl.model._
import akka.http.scaladsl.settings.{ ClientConnectionSettings, Http2CommonSettings, ParserSettings, ServerSettings }
import akka.stream.TLSProtocol._
import akka.stream.scaladsl.{ BidiFlow, Flow, Source }
import akka.util.ByteString

import scala.concurrent.duration.{ Duration, FiniteDuration }
import scala.concurrent.{ ExecutionContext, Future }
import scala.collection.immutable

/**
 * Represents one direction of an Http2 substream.
 */
private[http2] sealed trait Http2SubStream {
  val initialHeaders: ParsedHeadersFrame
  val data: Source[Any, Any]
  val correlationAttributes: Map[AttributeKey[_], _]
  def streamId: Int = initialHeaders.streamId

  def withCorrelationAttributes(attributes: Map[AttributeKey[_], _]): Http2SubStream

  def createRequestEntity(contentLength: Long, contentType: ContentType): RequestEntity =
    this match {
      case s if s.data == Source.empty || contentLength == 0 => HttpEntity.Empty
      case s: ByteHttp2SubStream =>
        if (contentLength > 0) HttpEntity.Default(contentType, contentLength, s.data)
        else HttpEntity.Chunked.fromData(contentType, s.data)
      /* contentLength undefined */
      case c: ChunkedHttp2SubStream => HttpEntity.Chunked(contentType, c.data)
    }
}

private[http2] final case class ByteHttp2SubStream(
  initialHeaders:        ParsedHeadersFrame,
  data:                  Source[ByteString, Any],
  correlationAttributes: Map[AttributeKey[_], _] = Map.empty
) extends Http2SubStream {
  override def withCorrelationAttributes(attributes: Map[AttributeKey[_], _]): Http2SubStream =
    copy(correlationAttributes = attributes)
}

private[http2] final case class ChunkedHttp2SubStream(
  initialHeaders:        ParsedHeadersFrame,
  data:                  Source[HttpEntity.ChunkStreamPart, Any],
  correlationAttributes: Map[AttributeKey[_], _]                 = Map.empty
) extends Http2SubStream {
  override def withCorrelationAttributes(attributes: Map[AttributeKey[_], _]): Http2SubStream =
    copy(correlationAttributes = attributes)

  def createResponseEntity(contentLength: Long, contentType: ContentType): RequestEntity = {
    // Ignore trailing headers when content-length is defined
    if (contentLength == 0) HttpEntity.Empty
    else if (contentLength > 0) HttpEntity.Default(contentType, contentLength, data.collect { case Chunk(bytes, _) => bytes })
    else HttpEntity.Chunked(contentType, data)
  }
}

/** INTERNAL API */
@InternalApi
private[http] object Http2Blueprint {

  def serverStackTls(settings: ServerSettings, log: LoggingAdapter): BidiFlow[HttpResponse, SslTlsOutbound, SslTlsInbound, HttpRequest, NotUsed] =
    serverStack(settings, log) atop
      unwrapTls atop
      logTLSBidiBySetting("server-plain-text", settings.logUnencryptedNetworkBytes)

  // format: OFF
  def serverStack(
      settings: ServerSettings,
      log: LoggingAdapter,
      initialDemuxerSettings: immutable.Seq[Setting] = Nil,
      upgraded: Boolean = false): BidiFlow[HttpResponse, ByteString, ByteString, HttpRequest, NotUsed] =
    httpLayer(settings, log) atop
      demux(settings.http2Settings, initialDemuxerSettings, upgraded) atop
      FrameLogger.logFramesIfEnabled(settings.http2Settings.logFrames) atop // enable for debugging
      hpackCoding() atop
      framing(log) atop
      idleTimeoutIfConfigured(settings.idleTimeout)
  // LogByteStringTools.logToStringBidi("framing") atop // enable for debugging
  // format: ON

  def clientStack(settings: ClientConnectionSettings, log: LoggingAdapter): BidiFlow[HttpRequest, ByteString, ByteString, HttpResponse, NotUsed] =
    httpLayerClient(settings, log) atop
      demux(settings.http2Settings, Nil) atop
      FrameLogger.logFramesIfEnabled(settings.http2Settings.logFrames) atop // enable for debugging
      hpackCoding() atop
      framingClient(log) atop
      idleTimeoutIfConfigured(settings.idleTimeout)

  def httpLayerClient(settings: ClientConnectionSettings, log: LoggingAdapter): BidiFlow[HttpRequest, Http2SubStream, ChunkedHttp2SubStream, HttpResponse, NotUsed] = {
    // This is master header parser, every other usage should do .createShallowCopy()
    // HttpHeaderParser is not thread safe and should not be called concurrently,
    // the internal trie, however, has built-in protection and will do copy-on-write
    val masterHttpHeaderParser = HttpHeaderParser(settings.parserSettings, log)
    BidiFlow.fromFlows(
      Flow[HttpRequest].statefulMapConcat { () =>
        val renderer = RequestRendering.createRenderer(log)
        request => renderer(request) :: Nil
      },
      Flow[ChunkedHttp2SubStream].statefulMapConcat { () =>
        val headerParser = masterHttpHeaderParser.createShallowCopy()
        stream => ResponseParsing.parseResponse(headerParser)(stream) :: Nil
      }
    )
  }

  def idleTimeoutIfConfigured(timeout: Duration): BidiFlow[ByteString, ByteString, ByteString, ByteString, NotUsed] =
    timeout match {
      case f: FiniteDuration => HttpConnectionIdleTimeoutBidi(f, None)
      case _                 => BidiFlow.identity[ByteString, ByteString]
    }

  def framing(log: LoggingAdapter): BidiFlow[FrameEvent, ByteString, ByteString, FrameEvent, NotUsed] =
    BidiFlow.fromFlows(
      Flow[FrameEvent].via(new Http2FrameRendering),
      Flow[ByteString].via(new Http2FrameParsing(shouldReadPreface = true, log)))

  def framingClient(log: LoggingAdapter): BidiFlow[FrameEvent, ByteString, ByteString, FrameEvent, NotUsed] =
    BidiFlow.fromFlows(
      Flow[FrameEvent].via(new Http2FrameRendering).prepend(Source.single(Http2Protocol.ClientConnectionPreface)),
      Flow[ByteString].via(new Http2FrameParsing(shouldReadPreface = false, log)))

  /**
   * Runs hpack encoding and decoding. Incoming frames that are processed are HEADERS and CONTINUATION.
   * Outgoing frame is ParsedHeadersFrame.
   * Other frames are propagated unchanged.
   *
   * TODO: introduce another FrameEvent type that exclude HeadersFrame and ContinuationFrame from
   * reaching the higher-level.
   */
  def hpackCoding(): BidiFlow[FrameEvent, FrameEvent, FrameEvent, FrameEvent, NotUsed] =
    BidiFlow.fromFlows(
      Flow[FrameEvent].via(HeaderCompression),
      Flow[FrameEvent].via(HeaderDecompression)
    )

  /**
   * Creates substreams for every stream and manages stream state machines
   * and handles priorization (TODO: later)
   */
  def demux(settings: Http2CommonSettings, initialDemuxerSettings: immutable.Seq[Setting], upgraded: Boolean): BidiFlow[Http2SubStream, FrameEvent, FrameEvent, Http2SubStream, NotUsed] =
    BidiFlow.fromGraph(new Http2ServerDemux(settings, initialDemuxerSettings, upgraded))

  /**
   * Creates substreams for every stream and manages stream state machines
   * and handles priorization (TODO: later)
   */
  def demux(settings: Http2CommonSettings, initialDemuxerSettings: immutable.Seq[Setting]): BidiFlow[Http2SubStream, FrameEvent, FrameEvent, ChunkedHttp2SubStream, NotUsed] =
    BidiFlow.fromGraph(new Http2ClientDemux(settings, initialDemuxerSettings))

  /**
   * Translation between substream frames and Http messages (both directions)
   *
   * To make use of parallelism requests and responses need to be associated (other than by ordering), suggestion
   * is to add a special (virtual) header containing the streamId (or any other kind of token) is added to the HttRequest
   * that must be reproduced in an HttpResponse. This can be done automatically for the `bind`` API but for
   * `bindFlow` the user needs to take of this manually.
   */
  def httpLayer(settings: ServerSettings, log: LoggingAdapter): BidiFlow[HttpResponse, Http2SubStream, Http2SubStream, HttpRequest, NotUsed] = {
    val parserSettings = settings.parserSettings
    // This is master header parser, every other usage should do .createShallowCopy()
    // HttpHeaderParser is not thread safe and should not be called concurrently,
    // the internal trie, however, has built-in protection and will do copy-on-write
    val masterHttpHeaderParser = HttpHeaderParser(parserSettings, log)
    BidiFlow.fromFlows(
      Flow[HttpResponse].map(ResponseRendering.renderResponse(settings, log)),
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
    BidiFlow.fromFlows(Flow[ByteString].map(SendBytes), Flow[SslTlsInbound].collect {
      case SessionBytes(_, bytes) => bytes
    })
}
