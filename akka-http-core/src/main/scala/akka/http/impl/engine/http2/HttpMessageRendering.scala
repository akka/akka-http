/*
 * Copyright (C) 2009-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import java.util.concurrent.atomic.AtomicInteger
import akka.annotation.InternalApi
import akka.event.LoggingAdapter
import akka.http.impl.engine.http2.FrameEvent.ParsedHeadersFrame
import akka.http.impl.engine.rendering.DateHeaderRendering
import akka.http.scaladsl.model._
import akka.http.scaladsl.settings.ClientConnectionSettings
import akka.http.scaladsl.settings.ServerSettings
import akka.util.OptionVal

import scala.collection.immutable
import scala.collection.immutable.VectorBuilder

/** INTERNAL API */
@InternalApi
private[http2] class ResponseRendering(settings: ServerSettings, val log: LoggingAdapter, val dateHeaderRendering: DateHeaderRendering) extends MessageRendering[HttpResponse] {

  private def failBecauseOfMissingAttribute: Nothing =
    // attribute is missing, shutting down because we will most likely otherwise miss a response and leak a substream
    // TODO: optionally a less drastic measure would be only resetting all the active substreams
    throw new RuntimeException("Received response for HTTP/2 request without x-http2-stream-id attribute. Failing connection.")

  protected override def nextStreamId(response: HttpResponse): Int = response.attribute(Http2.streamId).getOrElse(failBecauseOfMissingAttribute)

  protected override def initialHeaderPairs(response: HttpResponse): VectorBuilder[(String, String)] = {
    val headerPairs = new VectorBuilder[(String, String)]()
    // From https://tools.ietf.org/html/rfc7540#section-8.1.2.4:
    //   HTTP/2 does not define a way to carry the version or reason phrase
    //   that is included in an HTTP/1.1 status line.
    headerPairs += ":status" -> response.status.intValue.toString
  }

  override lazy val peerIdHeader: Option[(String, String)] = settings.serverHeader.map(h => h.lowercaseName -> h.value)

}

/** INTERNAL API */
@InternalApi
private[http2] class RequestRendering(settings: ClientConnectionSettings, val log: LoggingAdapter) extends MessageRendering[HttpRequest] {

  private val streamId = new AtomicInteger(1)
  protected override def nextStreamId(r: HttpRequest): Int = streamId.getAndAdd(2)

  protected override def initialHeaderPairs(request: HttpRequest): VectorBuilder[(String, String)] = {
    val headerPairs = new VectorBuilder[(String, String)]()
    headerPairs += ":method" -> request.method.value
    headerPairs += ":scheme" -> request.uri.scheme
    headerPairs += ":authority" -> request.uri.authority.toString
    headerPairs += ":path" -> request.uri.toHttpRequestTargetOriginForm.toString
    headerPairs
  }

  override lazy val peerIdHeader: Option[(String, String)] = settings.userAgentHeader.map(h => h.lowercaseName -> h.value)

  override protected def dateHeaderRendering: DateHeaderRendering = DateHeaderRendering.Unavailable
}

/** INTERNAL API */
@InternalApi
private[http2] sealed abstract class MessageRendering[R <: HttpMessage] extends (R => Http2SubStream) {

  protected def log: LoggingAdapter
  protected def nextStreamId(r: R): Int
  protected def initialHeaderPairs(r: R): VectorBuilder[(String, String)]
  protected def peerIdHeader: Option[(String, String)]
  protected def dateHeaderRendering: DateHeaderRendering

  def apply(r: R): Http2SubStream = {
    val headerPairs = initialHeaderPairs(r)

    HttpMessageRendering.addContentHeaders(headerPairs, r.entity)
    HttpMessageRendering.renderHeaders(r.headers, headerPairs, peerIdHeader, log, isServer = r.isResponse, shouldRenderAutoHeaders = true, dateHeaderRendering)

    val streamId = nextStreamId(r)
    val headersFrame = ParsedHeadersFrame(streamId, endStream = r.entity.isKnownEmpty, headerPairs.result(), None)
    val trailingHeadersFrame =
      r.attribute(AttributeKeys.trailer) match {
        case Some(trailer) => OptionVal.Some(ParsedHeadersFrame(streamId, endStream = true, trailer.headers, None))
        case None          => OptionVal.None
      }

    Http2SubStream(r.entity, headersFrame, trailingHeadersFrame, r.attributes.filter(_._2.isInstanceOf[RequestResponseAssociation]))
  }
}

/** INTERNAL API */
@InternalApi
private[http2] object HttpMessageRendering {
  /**
   * Mutates `headerPairs` adding headers related to content (type and length).
   */
  def addContentHeaders(headerPairs: VectorBuilder[(String, String)], entity: HttpEntity): Unit = {
    if (entity.contentType != ContentTypes.NoContentType)
      headerPairs += "content-type" -> entity.contentType.toString
    entity.contentLengthOption.foreach(headerPairs += "content-length" -> _.toString)
  }

  def renderHeaders(
    headers:                 immutable.Seq[HttpHeader],
    log:                     LoggingAdapter,
    isServer:                Boolean,
    shouldRenderAutoHeaders: Boolean,
    dateHeaderRendering:     DateHeaderRendering
  ): Seq[(String, String)] = {
    val headerPairs = new VectorBuilder[(String, String)]()
    renderHeaders(headers, headerPairs, None, log, isServer, shouldRenderAutoHeaders, dateHeaderRendering)
    headerPairs.result()
  }

  /**
   * Mutates `headerPairs` adding any valid header from `headersSeq`.
   * @param peerIdHeader a header providing extra information (e.g. vendor and version) about the
   *                     peer. For example, a User-Agent on the client or a Server header on the server.
   */
  def renderHeaders(
    headersSeq:              immutable.Seq[HttpHeader],
    headerPairs:             VectorBuilder[(String, String)],
    peerIdHeader:            Option[(String, String)],
    log:                     LoggingAdapter,
    isServer:                Boolean,
    shouldRenderAutoHeaders: Boolean,
    dateHeaderRendering:     DateHeaderRendering
  ): Unit = {
    def suppressionWarning(h: HttpHeader, msg: String): Unit =
      log.warning("Explicitly set HTTP header '{}' is ignored, {}", h, msg)

    val it = headersSeq.iterator
    var peerIdSeen, dateSeen = false
    def addHeader(h: HttpHeader): Unit = headerPairs += h.lowercaseName -> h.value

    while (it.hasNext) {
      import akka.http.scaladsl.model.headers._
      val header = it.next()
      if ((header.renderInResponses && isServer) || (header.renderInRequests && !isServer)) {
        header match {
          case x: Server if isServer =>
            addHeader(x)
            peerIdSeen = true

          case x: `User-Agent` if !isServer =>
            addHeader(x)
            peerIdSeen = true

          case x: Date if isServer =>
            addHeader(x)
            dateSeen = true

          case x: CustomHeader =>
            addHeader(x)

          case x: RawHeader if (x is "content-type") || (x is "content-length") || (x is "transfer-encoding") ||
            (x is "date") || (x is "server") || (x is "user-agent") || (x is "connection") =>
            suppressionWarning(x, "illegal RawHeader")

          case x: `Content-Length` =>
            suppressionWarning(x, "explicit `Content-Length` header is not allowed. Use the appropriate HttpEntity subtype.")

          case x: `Content-Type` =>
            suppressionWarning(x, "explicit `Content-Type` header is not allowed. Set `HttpResponse.entity.contentType` instead.")

          case x: `Transfer-Encoding` =>
            suppressionWarning(x, "`Transfer-Encoding` header is not allowed for HTTP/2")

          case x: Connection =>
            suppressionWarning(x, "`Connection` header is not allowed for HTTP/2")

          case x =>
            addHeader(x)
        }
      }
    }

    if (shouldRenderAutoHeaders && !dateSeen && isServer) {
      headerPairs += dateHeaderRendering.renderHeaderPair()
    }

    if (shouldRenderAutoHeaders && !peerIdSeen) {
      peerIdHeader match {
        case Some(peerIdTuple) => headerPairs += peerIdTuple
        case None              =>
      }
    }

  }

}
