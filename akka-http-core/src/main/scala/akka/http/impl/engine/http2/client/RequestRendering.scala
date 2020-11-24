/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2
package client

import java.util.concurrent.atomic.AtomicInteger

import akka.annotation.InternalApi
import akka.event.LoggingAdapter
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.settings.ClientConnectionSettings

import scala.collection.immutable.VectorBuilder

@InternalApi
private[http2] object RequestRendering {

  def createRenderer(settings: ClientConnectionSettings, log: LoggingAdapter): HttpRequest => Http2SubStream = {
    val streamId = new AtomicInteger(1)

    new CommonRendering[HttpRequest] {

      override def nextStreamId(r: HttpRequest): Int = streamId.getAndAdd(2)

      override def initialHeaderPairs(request: HttpRequest): VectorBuilder[(String, String)] = {
        val headerPairs = new VectorBuilder[(String, String)]()
        headerPairs += ":method" -> request.method.value
        headerPairs += ":scheme" -> request.uri.scheme
        headerPairs += ":authority" -> request.uri.authority.toString
        headerPairs += ":path" -> request.uri.toHttpRequestTargetOriginForm.toString
        headerPairs
      }

      override lazy val peerIdHeader: Option[(String, String)] = settings.userAgentHeader.map(h => h.lowercaseName -> h.value)

    }.createRenderer(log)

  }
}
