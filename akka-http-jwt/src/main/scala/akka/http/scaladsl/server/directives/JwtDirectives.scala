package akka.http.scaladsl.server.directives

import akka.event.LoggingAdapter
import akka.http.jwt.util.JwtSupport
import akka.http.scaladsl.server.{ Directive1, ExceptionHandler, InvalidRequiredValueForQueryParamRejection, JwtRejection, MalformedQueryParamRejection, MalformedRequestContentRejection, MissingQueryParamRejection, RequestContext }
import akka.http.scaladsl.server.Directives.{ Authenticator, AuthenticatorPF, authenticateOAuth2, authenticateOAuth2Async, authenticateOAuth2PF, handleExceptions, headerValueByName }
import com.typesafe.config.Config
import spray.json.{ JsBoolean, JsNumber, JsObject, JsString, JsValue }

trait JwtDirectives {

  import BasicDirectives._
  import RouteDirectives._

  def jwt(): Directive1[JwtClaims] = {
    extractActorSystem.flatMap { system =>
      jwt(system.settings.config)
    }
  }

  def jwt(settings: Config): Directive1[JwtClaims] = {
    extractLog.flatMap { log =>
      // FIXME: Should we use this or just manually export the header value? Is there a clever way to extract a realm that makes sense? path?
      authenticateOAuth2("realm", bearerTokenAuthenticator(settings, log)).flatMap { claims =>
        extractRequestContext.flatMap { ctx =>
          provide(new JwtClaims(ctx, claims))
        }
      }
    }
  }

  private def bearerTokenAuthenticator(settings: Config, log: LoggingAdapter): Authenticator[JsObject] = {
    case p @ Credentials.Provided(token) =>
      val jwtSupport = JwtSupport.fromConfig(settings)

      jwtSupport.validate(token) match {
        case Right(claims) => Some(claims)
        case Left(ex) =>
          log.debug("The token was rejected: {}", ex.getMessage)
          None // FIXME: how to propagate this?
      }
  }

  // JwtClaims provides utilities to easily assert and extract claims from the JWT token
  class JwtClaims(ctx: RequestContext, claims: JsObject) {

    def hasClaim(name: String): Boolean = claims.fields.contains(name)

    def intClaim(name: String): Option[Int] = claims.fields.get(name).collect { case JsNumber(value) => value.toInt }

    def longClaim(name: String): Option[Long] = claims.fields.get(name).collect { case JsNumber(value) => value.toLong }

    def doubleClaim(name: String): Option[Double] = claims.fields.get(name).collect { case JsNumber(value) => value.toDouble }

    def stringClaim(name: String): Option[String] = claims.fields.get(name).collect { case JsString(value) => value }

    def booleanClaim(name: String): Option[Boolean] = claims.fields.get(name).collect { case JsBoolean(value) => value }

    def toJson: String = claims.toString()
  }

}

object JwtDirectives extends JwtDirectives

