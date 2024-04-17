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

/**
 * JwtDirectives provides utilities to easily assert and extract claims from a JSON Web Token (JWT).
 *
 * For more information about JWTs, see [[https://jwt.io/]] or consult RFC 7519: [[https://datatracker.ietf.org/doc/html/rfc7519]]
 */
abstract class JwtDirectives {

  /**
   * Wraps its inner route with support for the JWT mechanism, enabling JWT token validation.
   * JWT token validation is done automatically extracting the token from the Authorization header.
   * If the token is valid, the inner route is executed and provided the set of claims as [[JwtClaims]],
   * otherwise a 401 Unauthorized response is returned.
   */
  def jwt(inner: JFunction[JwtClaims, Route]): Route = RouteAdapter {
    JD.jwt() { claims =>
      inner.apply(claims.asInstanceOf[JwtClaimsImpl]).delegate
    }
  }

  /**
   * Wraps its inner route with support for the JWT mechanism, enabling JWT token validation using the given jwt settings.
   * JWT token validation is done automatically extracting the token from the Authorization header.
   * If the token is valid, the inner route is executed and provided the set of claims as [[JwtClaims]],
   * otherwise a 401 Unauthorized response is returned.
   */
  def jwt(settings: JwtSettings, inner: JFunction[JwtClaims, Route]): Route = RouteAdapter {
    JD.jwt(settings.asInstanceOf[akka.http.jwt.scaladsl.JwtSettings]) { claims =>
      inner.apply(claims.asInstanceOf[JwtClaimsImpl]).delegate
    }
  }
}
