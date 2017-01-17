/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.http.scaladsl.model.{ HttpHeader, HttpRequest }

import scala.collection.immutable
import scala.collection.immutable.VectorBuilder

object RequestRendering {
  def renderRequestToHeaderPairs(request: HttpRequest): immutable.Seq[(String, String)] = {
    val headerPairs = new VectorBuilder[(String, String)]()

    // scheme, method, path, authority
    headerPairs += ":method" → request.method.value
    headerPairs += ":scheme" → request.uri.scheme
    headerPairs += ":authority" → request.uri.authority.toString.drop(2) // Authority.toString renders leading double slashes see #784
    headerPairs += ":path" → request.uri.path.toString

    headerPairs ++=
      request.headers.collect {
        case header: HttpHeader if header.renderInResponses ⇒ header.lowercaseName → header.value
      }

    headerPairs.result()
  }
}
