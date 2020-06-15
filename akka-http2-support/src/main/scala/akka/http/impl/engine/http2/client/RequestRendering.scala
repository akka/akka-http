/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2
package client

import java.util.concurrent.atomic.AtomicInteger

import akka.annotation.InternalApi
import akka.event.LoggingAdapter
import akka.http.impl.engine.http2.FrameEvent.ParsedHeadersFrame
import akka.http.scaladsl.model.http2.RequestResponseAssociation
import akka.http.scaladsl.model.{ ContentTypes, HttpEntity, HttpRequest }

import scala.collection.immutable.VectorBuilder

@InternalApi
private[http2] object RequestRendering {
  def createRenderer(log: LoggingAdapter): HttpRequest => Http2SubStream = {
    val streamId = new AtomicInteger(1)

    { request =>
      val headerPairs = new VectorBuilder[(String, String)]()
      headerPairs += ":method" -> request.method.value
      headerPairs += ":scheme" -> "https" // FIXME: should that be the real scheme?
      headerPairs += ":authority" -> request.uri.authority.toString
      headerPairs += ":path" -> request.uri.toHttpRequestTargetOriginForm.toString

      if (request.entity.contentType != ContentTypes.NoContentType)
        headerPairs += "content-type" -> request.entity.contentType.toString
      request.entity.contentLengthOption.foreach(headerPairs += "content-length" -> _.toString)
      ResponseRendering.renderHeaders(request.headers, headerPairs, None /* FIXME: render user agent */ , log, isServer = false)

      val headersFrame = ParsedHeadersFrame(streamId.getAndAdd(2), endStream = request.entity.isKnownEmpty, headerPairs.result(), None)

      val substream =
        request.entity match {
          case HttpEntity.Chunked(_, chunks) =>
            ChunkedHttp2SubStream(headersFrame, chunks)
          case _ =>
            ByteHttp2SubStream(headersFrame, request.entity.dataBytes)
        }
      substream.withCorrelationAttributes(request.attributes.filter(_._2.isInstanceOf[RequestResponseAssociation]))
    }
  }
}
