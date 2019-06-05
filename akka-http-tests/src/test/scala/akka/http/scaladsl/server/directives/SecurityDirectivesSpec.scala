/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.server
package directives

import scala.concurrent.Future
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.server.AuthenticationFailedRejection.{ CredentialsRejected, CredentialsMissing }
import akka.testkit.EventFilter

class SecurityDirectivesSpec extends RoutingSpec {
  val doBasicAuth = authenticateBasicPF("MyRealm", { case Credentials.Provided(identifier) => identifier })
  val dontBasicAuth = authenticateBasicAsync[String]("MyRealm", _ => Future.successful(None))
  val basicChallenge = HttpChallenges.basic("MyRealm")

  val doOAuth2Auth = authenticateOAuth2PF("MyRealm", { case Credentials.Provided(identifier) => identifier })
  val dontOAuth2Auth = authenticateOAuth2Async[String]("MyRealm", _ => Future.successful(None))
  val oAuth2Challenge = HttpChallenges.oAuth2("MyRealm")

  authenticationTests("Basic", doBasicAuth, dontBasicAuth, basicChallenge, BasicHttpCredentials(_, ""))
  authenticationTests("OAuth2 Bearer token", doOAuth2Auth, dontOAuth2Auth, oAuth2Challenge, OAuth2BearerToken(_))

  def authenticationTests(
    authenticationType:      String,
    successfulAuthDirective: AuthenticationDirective[String],
    failedAuthDirective:     AuthenticationDirective[String],
    challenge:               HttpChallenge,
    createCredentials:       String => HttpCredentials
  ): Unit =
    s"$authenticationType authentication" should {
      "reject requests without Authorization header with an AuthenticationFailedRejection" in {
        Get() ~> {
          failedAuthDirective { echoComplete }
        } ~> check { rejection shouldEqual AuthenticationFailedRejection(CredentialsMissing, challenge) }
      }
      "reject unauthenticated requests with Authorization header with an AuthenticationFailedRejection" in {
        Get() ~> Authorization(createCredentials("Bob")) ~> {
          failedAuthDirective { echoComplete }
        } ~> check { rejection shouldEqual AuthenticationFailedRejection(CredentialsRejected, challenge) }
      }
      "reject requests with an any other credentials in Authorization header with 401" in {
        Get() ~> Authorization(GenericHttpCredentials("Other", "token")) ~> Route.seal {
          successfulAuthDirective { echoComplete }
        } ~> check {
          status shouldEqual StatusCodes.Unauthorized
          responseAs[String] shouldEqual "The resource requires authentication, which was not supplied with the request"
          header[`WWW-Authenticate`] shouldEqual Some(`WWW-Authenticate`(challenge))
        }
      }
      "reject requests with illegal Authorization header with 401" in {
        Get() ~> RawHeader("Authorization", "bob alice") ~> Route.seal {
          failedAuthDirective { echoComplete }
        } ~> check {
          status shouldEqual StatusCodes.Unauthorized
          responseAs[String] shouldEqual "The resource requires authentication, which was not supplied with the request"
          header[`WWW-Authenticate`] shouldEqual Some(`WWW-Authenticate`(challenge))
        }
      }
      "extract the object representing the user identity created by successful authentication" in {
        Get() ~> Authorization(createCredentials("Alice")) ~> {
          successfulAuthDirective { echoComplete }
        } ~> check { responseAs[String] shouldEqual "Alice" }
      }
      "extract the object representing the user identity created for the anonymous user" in {
        Get() ~> {
          successfulAuthDirective.withAnonymousUser("We are Legion") { echoComplete }
        } ~> check { responseAs[String] shouldEqual "We are Legion" }
      }
      "handle exceptions thrown in its inner route" in {
        object TestException extends RuntimeException("Boom")
        EventFilter[TestException.type](
          occurrences = 1,
          start = "Error during processing of request: 'Boom'. Completing with 500 Internal Server Error response."
        ).intercept {
          Get() ~> Authorization(createCredentials("Alice")) ~> {
            Route.seal {
              successfulAuthDirective { _ => throw TestException }
            }
          } ~> check { status shouldEqual StatusCodes.InternalServerError }
        }
      }
    }

  "authentication directives" should {
    "stack" in {
      val otherChallenge = HttpChallenge("MyAuth", Some("MyRealm2"))
      val otherAuth: Directive1[String] = authenticateOrRejectWithChallenge { (cred: Option[HttpCredentials]) =>
        Future.successful(Left(otherChallenge))
      }
      val bothAuth = dontBasicAuth | otherAuth

      Get() ~> Route.seal(bothAuth { echoComplete }) ~> check {
        status shouldEqual StatusCodes.Unauthorized
        headers.collect {
          case `WWW-Authenticate`(challenge +: Nil) => challenge
        } shouldEqual Seq(basicChallenge, otherChallenge)
      }
    }
  }

  "authorization directives" should {
    "authorize" in {
      Get() ~> {
        authorize(_ => true) { complete("OK") }
      } ~> check { responseAs[String] shouldEqual "OK" }
    }
    "not authorize" in {
      Get() ~> {
        authorize(_ => false) { complete("OK") }
      } ~> check { rejection shouldEqual AuthorizationFailedRejection }
    }

    "authorizeAsync" in {
      Get() ~> {
        authorizeAsync(_ => Future.successful(true)) { complete("OK") }
      } ~> check { responseAs[String] shouldEqual "OK" }
    }
    "not authorizeAsync" in {
      Get() ~> {
        authorizeAsync(_ => Future.successful(false)) { complete("OK") }
      } ~> check { rejection shouldEqual AuthorizationFailedRejection }
    }
    "not authorizeAsync when future fails" in {
      Get() ~> {
        authorizeAsync(_ => Future.failed(new Exception("Boom!"))) { complete("OK") }
      } ~> check { rejection shouldEqual AuthorizationFailedRejection }
    }
  }

}
