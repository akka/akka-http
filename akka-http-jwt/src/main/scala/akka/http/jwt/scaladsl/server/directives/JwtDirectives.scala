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

trait JwtDirectives {

  def jwt(): Directive1[JwtClaims] = {
    extractActorSystem.flatMap { system =>
      jwt(scaladsl.JwtSettings(system))
    }
  }

  def jwt(settings: scaladsl.JwtSettings): Directive1[JwtClaims] = {
    extractLog.flatMap { log =>
      authenticateOAuth2(settings.realm, bearerTokenAuthenticator(settings.jwtSupport, log)).flatMap { claims =>
        provide(new JwtClaimsImpl(claims))
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

