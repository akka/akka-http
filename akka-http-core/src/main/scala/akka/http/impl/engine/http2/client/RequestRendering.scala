/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2
package client

import java.util.concurrent.atomic.AtomicInteger

import akka.annotation.InternalApi
import akka.event.LoggingAdapter
import akka.http.impl.engine.http2.FrameEvent.ParsedHeadersFrame
import akka.http.scaladsl.model.{ HttpRequest, RequestResponseAssociation }
import akka.util.ByteString

import scala.collection.immutable.VectorBuilder

@InternalApi
private[http2] object RequestRendering {
  def createRenderer(log: LoggingAdapter): HttpRequest => Http2SubStream[Any] = {
    val streamId = new AtomicInteger(1)

    { request =>
      val headerPairs = new VectorBuilder[(String, String)]()
      headerPairs += ":method" -> request.method.value
      headerPairs += ":scheme" -> request.uri.scheme
      headerPairs += ":authority" -> request.uri.authority.toString
      headerPairs += ":path" -> request.uri.toHttpRequestTargetOriginForm.toString

      ResponseRendering.addContentHeaders(headerPairs, request.entity)
      ResponseRendering.renderHeaders(request.headers, headerPairs, None /* FIXME: render user agent */ , log, isServer = false)

      val headersFrame = ParsedHeadersFrame(streamId.getAndAdd(2), endStream = request.entity.isKnownEmpty, headerPairs.result(), None)

      val substream = ResponseRendering.substreamFor(request.entity, headersFrame)
      substream.withCorrelationAttributes(request.attributes.filter(_._2.isInstanceOf[RequestResponseAssociation]))
    }
  }
}
