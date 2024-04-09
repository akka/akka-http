package akka.http.scaladsl.server.directives

import akka.event.LoggingAdapter
import akka.http.impl.util.enhanceString_
import akka.http.scaladsl.common.{ NameDefaultReceptacle, NameOptionReceptacle, NameReceptacle, RequiredValueReceptacle }
import akka.http.scaladsl.model.{ AttributeKey, HttpRequest }
import akka.http.scaladsl.server.{ Directive, Directive0, Directive1, InvalidRequiredValueForQueryParamRejection, MalformedQueryParamRejection, MalformedRequestContentRejection, MissingQueryParamRejection, RequestContext }
import akka.http.scaladsl.server.Directives.{ Authenticator, AuthenticatorPF, authenticateOAuth2, authenticateOAuth2Async, authenticateOAuth2PF, headerValueByName }
import akka.http.scaladsl.server.directives.BasicDirectives.{ extractRequestContext, pass, provide }
import akka.http.scaladsl.server.directives.FutureDirectives.onComplete
import akka.http.scaladsl.server.directives.RouteDirectives.reject
import akka.http.scaladsl.server.util.{ JwtSprayJson, JwtSupport }
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.http.scaladsl.util.FastFuture
import com.typesafe.config.Config
import pdi.jwt.JwtClaim
import spray.json.{ JsBoolean, JsNumber, JsObject, JsString, JsValue }

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ Await, Future }
import scala.reflect.ClassTag
import scala.util.{ Failure, Success }

trait JwtDirectives {

  import BasicDirectives._
  import RouteDirectives._

  def jwt(): Directive1[JwtClaims] = {
    extractActorSystem.flatMap { system =>
      jwt(system.settings.config)
    }
  }

  private def jwt(settings: Config): Directive1[JwtClaims] = {
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

}

// JwtClaims provides utilities to easily assert and extract claims from the JWT token
class JwtClaims(ctx: RequestContext, claims: JsObject) {

  import akka.http.scaladsl.unmarshalling.{ FromStringUnmarshaller => FSU }
  import ctx.executionContext
  import ctx.materializer

  def hasClaim(name: String): Boolean = claims.fields.contains(name)

  def get[T](name: String)(implicit fsu: FSU[T]): Future[T] = getClaim[T](name)

  def get[T](name: String, default: T)(implicit fsu: FSU[T]): Future[T] = {
    getOpt(name).map {
      case Some(value) => value
      case None        => default
    }
  }

  def getOpt[T](name: String)(implicit fsu: FSU[T]): Future[Option[T]] = {
    if (!hasClaim(name))
      FastFuture.successful(None)
    else
      extractClaim(name).map(Some(_))
  }

  private def getClaim[T](name: String)(implicit fsu: FSU[T]): Future[T] = {
    getOpt(name).flatMap {
      case Some(value) => FastFuture.successful(value)
      case None        => FastFuture.failed(new NoSuchElementException(s"Claim $name not found"))
    }
  }

  private def extractClaim[T](name: String)(implicit fsu: FSU[T]): Future[T] = {
    val result = claims.fields.get(name) match {
      case Some(JsString(value))  => fsu(Some(value))
      case Some(JsBoolean(value)) => fsu(Some(value.toString))
      case Some(JsNumber(value))  => fsu(Some(value.toString()))
      case _                      => fsu(None)
    }
    result.transform(identity, e => new NoSuchElementException(s"Claim $name not found"))
  }

  def toJson: String = claims.toString()
}
