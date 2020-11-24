/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.event.LoggingAdapter
import akka.http.impl.engine.http2.FrameEvent.ParsedHeadersFrame
import akka.http.impl.util.StringRendering
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.Date
import akka.http.scaladsl.settings.ServerSettings

import scala.collection.immutable
import scala.collection.immutable.VectorBuilder

private[http2] object ResponseRendering {

  def renderResponse(settings: ServerSettings, log: LoggingAdapter): HttpResponse => Http2SubStream = {
    def failBecauseOfMissingAttribute: Nothing =
      // attribute is missing, shutting down because we will most likely otherwise miss a response and leak a substream
      // TODO: optionally a less drastic measure would be only resetting all the active substreams
      throw new RuntimeException("Received response for HTTP/2 request without x-http2-stream-id attribute. Failing connection.")

    new CommonRendering[HttpResponse] {
      override def nextStreamId(response: HttpResponse): Int = response.attribute(Http2.streamId).getOrElse(failBecauseOfMissingAttribute)

      override def initialHeaderPairs(response: HttpResponse): VectorBuilder[(String, String)] = {
        val headerPairs = new VectorBuilder[(String, String)]()
        // From https://tools.ietf.org/html/rfc7540#section-8.1.2.4:
        //   HTTP/2 does not define a way to carry the version or reason phrase
        //   that is included in an HTTP/1.1 status line.
        headerPairs += ":status" -> response.status.intValue.toString
      }

      override lazy val peerIdHeader: Option[(String, String)] = settings.serverHeader.map(h => h.lowercaseName -> h.value)
    }.createRenderer(log)

  }
}

trait CommonRendering[R <: HttpMessage] {

  def nextStreamId(r: R): Int
  def initialHeaderPairs(r: R): VectorBuilder[(String, String)]
  def peerIdHeader: Option[(String, String)]

  def createRenderer(log: LoggingAdapter): R => Http2SubStream = { (r: R) =>

    val headerPairs = initialHeaderPairs(r)

    CommonRendering.addContentHeaders(headerPairs, r.entity)
    CommonRendering.renderHeaders(r.headers, headerPairs, peerIdHeader, log, isServer = r.isResponse)

    val headersFrame = ParsedHeadersFrame(nextStreamId(r), endStream = r.entity.isKnownEmpty, headerPairs.result(), None)

    Http2SubStream(r.entity, headersFrame, r.attributes.filter(_._2.isInstanceOf[RequestResponseAssociation]))
  }
}

private[http2] object CommonRendering {

  @volatile
  private var cachedDateHeader = (0L, ("", ""))

  private def dateHeader(): (String, String) = {
    val cachedSeconds = cachedDateHeader._1
    val now = System.currentTimeMillis()
    if (now / 1000 > cachedSeconds) {
      val r = new StringRendering
      DateTime(now).renderRfc1123DateTimeString(r)
      cachedDateHeader = (now, Date.lowercaseName -> r.get)
    }
    cachedDateHeader._2
  }

  /**
   * Mutates `headerPairs` adding headers related to content (type and length).
   */
  private[http2] def addContentHeaders(headerPairs: VectorBuilder[(String, String)], entity: HttpEntity): Unit = {
    if (entity.contentType != ContentTypes.NoContentType)
      headerPairs += "content-type" -> entity.contentType.toString
    entity.contentLengthOption.foreach(headerPairs += "content-length" -> _.toString)
  }

  private[http2] def renderHeaders(
    headers:  immutable.Seq[HttpHeader],
    log:      LoggingAdapter,
    isServer: Boolean
  ): Seq[(String, String)] = {
    val headerPairs = new VectorBuilder[(String, String)]()
    renderHeaders(headers, headerPairs, None, log, isServer)
    headerPairs.result()
  }

  /**
   * Mutates `headerPairs` adding any valid header from `headersSeq`.
   * @param peerIdHeader a header providing extra information (e.g. vendor and version) about the
   *                     peer. For example, a User-Agent on the client or a Server header on the server.
   */
  private[http2] def renderHeaders(
    headersSeq:   immutable.Seq[HttpHeader],
    headerPairs:  VectorBuilder[(String, String)],
    peerIdHeader: Option[(String, String)],
    log:          LoggingAdapter,
    isServer:     Boolean
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

          case x: Date =>
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

    if (!dateSeen) {
      headerPairs += dateHeader()
    }

    if (!peerIdSeen) {
      peerIdHeader match {
        case Some(peerIdTuple) => headerPairs += peerIdTuple
        case None              =>
      }
    }

  }

}
