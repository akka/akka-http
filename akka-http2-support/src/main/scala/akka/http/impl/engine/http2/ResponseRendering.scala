/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.http.impl.engine.rendering.AbstractHeaderRenderer
import akka.http.scaladsl.model.headers.Connection
import akka.http.scaladsl.model.{ ContentType, ContentTypes, HttpHeader, HttpResponse }
import akka.http.scaladsl.model.http2.Http2StreamIdHeader

import scala.collection.immutable.VectorBuilder

private[http2] object ResponseRendering {

  // best case it can be a stateless single instance like this
  private val renderer = new AbstractHeaderRenderer[VectorBuilder[(String, String)]] {
    override protected def render(h: HttpHeader, builder: VectorBuilder[(String, String)]): Unit = {
      builder += h.lowercaseName → h.value
    }

    override protected def suppressed(h: HttpHeader, msg: String): Unit = suppressed(h)
    override protected def suppressed(h: HttpHeader): Unit = println("TODO log suppressed header")

    override protected def renderDefaultServerHeader(builder: VectorBuilder[(String, String)]): Unit = {
      // TODO render server header efficiently
    }

    override protected def renderDateHeader(builder: VectorBuilder[(String, String)]): Unit = {
      // TODO render date header efficiently
    }

    // hook for protocol version specific auto headers/closing etc.
    override protected def headerRenderingComplete(
      builder:                                 VectorBuilder[(String, String)],
      mustRenderTransferEncodingChunkedHeader: Boolean,
      alwaysClose:                             Boolean,
      connHeader:                              Connection,
      serverSeen:                              Boolean,
      transferEncodingSeen:                    Boolean,
      dateSeen:                                Boolean): Unit = {
      // TODO Not sure if any of the flags should trigger any logic here
    }
  }

  def renderResponse(response: HttpResponse): Http2SubStream = {
    def failBecauseOfMissingHeader: Nothing =
      // header is missing, shutting down because we will most likely otherwise miss a response and leak a substream
      // TODO: optionally a less drastic measure would be only resetting all the active substreams
      throw new RuntimeException("Received response for HTTP/2 request without Http2StreamIdHeader. Failing connection.")

    val streamId = response.header[Http2StreamIdHeader].getOrElse(failBecauseOfMissingHeader).streamId
    val headerPairs = new VectorBuilder[(String, String)]()

    // From https://tools.ietf.org/html/rfc7540#section-8.1.2.4:
    //   HTTP/2 does not define a way to carry the version or reason phrase
    //   that is included in an HTTP/1.1 status line.
    headerPairs += ":status" → response.status.intValue.toString

    if (response.entity.contentType != ContentTypes.NoContentType)
      headerPairs += "content-type" → response.entity.contentType.toString

    renderer.renderHeaders(response.headers, headerPairs, mustRenderTransferEncodingChunkedHeader = false)
    response.entity.contentLengthOption.foreach(headerPairs += "content-length" → _.toString)

    val headers = ParsedHeadersFrame(streamId, endStream = response.entity.isKnownEmpty, headerPairs.result(), None)

    Http2SubStream(
      headers,
      response.entity.dataBytes
    )
  }
}
