/*
 * Copyright (C) 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.jwt.javadsl.server.directives

import akka.http.javadsl.server.Route
import akka.http.javadsl.server.directives.RouteAdapter
import akka.http.jwt.internal.JwtClaimsImpl
import akka.http.jwt.scaladsl.server.directives.{ JwtDirectives => JD }
import akka.http.jwt.javadsl.JwtSettings

import java.util.function.{ Function => JFunction }

abstract class JwtDirectives {

  // Wraps its inner route with support for the JWT mechanism, enabling JWT token validation.
  def jwt(inner: JFunction[JwtClaims, Route]): Route = RouteAdapter {
    JD.jwt() { claims =>
      inner.apply(claims.asInstanceOf[JwtClaimsImpl]).delegate
    }
  }

  // Wraps its inner route with support for the JWT mechanism, enabling JWT token validation using the given jwt
  // settings.
  def jwt(settings: JwtSettings, inner: JFunction[JwtClaims, Route]): Route = RouteAdapter {
    JD.jwt(settings.asInstanceOf[akka.http.jwt.scaladsl.JwtSettings]) { claims =>
      inner.apply(claims.asInstanceOf[JwtClaimsImpl]).delegate
    }
  }
}
