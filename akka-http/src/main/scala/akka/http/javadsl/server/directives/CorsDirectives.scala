/*
 * Copyright (C) 2024 Lightbend Inc. <https://www.lightbend.com>
 * Copyright 2016 Lomig Mégard
 */

package akka.http.javadsl.server.directives

import akka.http.javadsl.server.Route
import akka.http.scaladsl.server.directives.{ CorsDirectives => CD }
import akka.http.javadsl.settings.CorsSettings

import java.util.function.Supplier

/**
 * Directives for CORS, cross origin requests.
 *
 * For an overview on how CORS works, see the MDN web docs page on CORS: https://developer.mozilla.org/en-US/docs/Web/HTTP/CORS
 * CORS is part of the WHATWG Fetch "Living Standard" https://fetch.spec.whatwg.org/#http-cors-protocol
 *
 * This implementation is based on the akka-http-cors project by Lomig Mégard, licensed under the Apache License, Version 2.0.
 */
abstract class CorsDirectives extends FramedEntityStreamingDirectives {
  import akka.http.javadsl.server.RoutingJavaMapping.Implicits._
  import akka.http.javadsl.server.RoutingJavaMapping._

  /**
   * Wraps its inner route with support for the CORS mechanism, enabling cross origin requests.
   *
   * The settings are loaded from the Actor System configuration.
   */
  def cors(inner: Supplier[Route]): Route = RouteAdapter {
    CD.cors() {
      inner.get().delegate
    }
  }

  /**
   * Wraps its inner route with support for the CORS mechanism, enabling cross origin requests using the given cors
   * settings.
   */
  def cors(settings: CorsSettings, inner: Supplier[Route]): Route = RouteAdapter {
    CD.cors(settings.asInstanceOf[akka.http.scaladsl.settings.CorsSettings]) {
      inner.get().delegate
    }
  }
}
