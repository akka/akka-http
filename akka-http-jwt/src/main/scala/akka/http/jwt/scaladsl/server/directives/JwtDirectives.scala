/*
 * Copyright (C) 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.jwt.scaladsl.server.directives

import akka.event.LoggingAdapter
import akka.http.jwt.internal.{ JwtClaimsImpl, JwtSupport }
import akka.http.jwt.scaladsl
import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.server.directives.BasicDirectives.{ extractActorSystem, extractLog, provide }
import akka.http.scaladsl.server.directives.Credentials
import akka.http.scaladsl.server.directives.SecurityDirectives.{ Authenticator, authenticateOAuth2 }
import spray.json.JsObject

import scala.concurrent.duration.DurationInt

/**
 * JwtDirectives provides utilities to assert and extract claims from a JSON Web Token (JWT).
 *
 * For more information about JWTs, see [[https://jwt.io/]] or consult RFC 7519: [[https://datatracker.ietf.org/doc/html/rfc7519]]
 */
trait JwtDirectives {

  @volatile private var lastWarningTs: Long = 0L

  /**
   * Wraps its inner route with support for the JWT mechanism, enabling JWT token validation.
   * JWT token validation is done automatically extracting the token from the Authorization header.
   * If the token is valid, the inner route is executed and provided the set of claims as [[JwtClaims]],
   * otherwise a 401 Unauthorized response is returned.
   */
  def jwt(): Directive1[JwtClaims] = {
    extractActorSystem.flatMap { system =>
      jwt(scaladsl.JwtSettings(system))
    }
  }

  /**
   * Wraps its inner route with support for the JWT mechanism, enabling JWT token validation using the given jwt settings.
   * JWT token validation is done automatically extracting the token from the Authorization header.
   * If the token is valid, the inner route is executed and provided the set of claims as [[JwtClaims]],
   * otherwise a 401 Unauthorized response is returned.
   */
  def jwt(settings: scaladsl.JwtSettings): Directive1[JwtClaims] = {
    extractLog.flatMap { log =>
      // log once every minute if dev mode is enabled
      if (settings.devMode && (System.currentTimeMillis() - lastWarningTs) > 1.minute.toMillis) {
        log.warning("Dev mode is enabled thus JWT signatures are not verified. This must not be used in production. To disable, set config: 'akka.http.jwt.dev = off'")
        lastWarningTs = System.currentTimeMillis()
      }

      authenticateOAuth2(settings.realm, bearerTokenAuthenticator(settings.jwtSupport, log)).flatMap { claims =>
        provide(JwtClaimsImpl(claims))
      }
    }
  }

  private def bearerTokenAuthenticator(jwtSupport: JwtSupport, log: LoggingAdapter): Authenticator[JsObject] = {
    case p @ Credentials.Provided(token) =>
      jwtSupport.validate(token) match {
        case Right(claims) => Some(claims)
        case Left(ex) =>
          log.debug("The token was rejected: {}", ex.getMessage)
          None // FIXME: should we propagate anything else further?
      }
  }

}

object JwtDirectives extends JwtDirectives
