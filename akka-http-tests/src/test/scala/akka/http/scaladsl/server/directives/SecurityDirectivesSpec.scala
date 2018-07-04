/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.server
package directives

import scala.concurrent.Future
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.server.AuthenticationFailedRejection.{ CredentialsRejected, CredentialsMissing }
import akka.testkit.EventFilter

class SecurityDirectivesSpec extends RoutingSpec {
  val dontBasicAuth = authenticateBasicAsync[String]("MyRealm", _ ⇒ Future.successful(None))
  def dontBasicInRealmAuth(realm: String) = authenticateBasicAsync("MyRealm", { _ ⇒ Future.successful(None) })
  val dontOAuth2Auth = authenticateOAuth2Async[String]("MyRealm", _ ⇒ Future.successful(None))
  def dontOAuth2InRealmAuth(realm: String) = authenticateOAuth2Async("MyRealm", { _ ⇒ Future.successful(None) })
  val doBasicAuth = authenticateBasicPF("MyRealm", { case Credentials.Provided(identifier) ⇒ identifier })
  def doBasicInRealmAuth(realm: String) = authenticateBasicPF("MyRealm", { case Credentials.Provided.InRealm(identifier, `realm`) ⇒ identifier })
  val doOAuth2Auth = authenticateOAuth2PF("MyRealm", { case Credentials.Provided(identifier) ⇒ identifier })
  def doOAuth2InRealmAuth(realm: String) = authenticateOAuth2PF("MyRealm", { case Credentials.Provided.InRealm(identifier, `realm`) ⇒ identifier })
  val anonBasicAuth = doBasicAuth.withAnonymousUser("We are Legion")
  def anonBasicInRealmAuth(realm: String) = doBasicInRealmAuth(realm).withAnonymousUser("We are Legion")

  val basicChallenge = HttpChallenges.basic("MyRealm")
  val oAuth2Challenge = HttpChallenges.oAuth2("MyRealm")

  "basic authentication" should {
    "reject requests without Authorization header with an AuthenticationFailedRejection" in {
      Get() ~> {
        dontBasicAuth { echoComplete }
      } ~> check { rejection shouldEqual AuthenticationFailedRejection(CredentialsMissing, basicChallenge) }
    }
    "reject unauthenticated requests with Authorization header with an AuthenticationFailedRejection" in {
      Get() ~> Authorization(BasicHttpCredentials("Bob", "")) ~> {
        dontBasicAuth { echoComplete }
      } ~> check { rejection shouldEqual AuthenticationFailedRejection(CredentialsRejected, basicChallenge) }
    }
    "reject requests with a Basic Authorization header with 401" in {
      Get() ~> Authorization(BasicHttpCredentials("Alice", "")) ~> Route.seal {
        dontBasicAuth { echoComplete }
      } ~> check {
        status shouldEqual StatusCodes.Unauthorized
        responseAs[String] shouldEqual "The supplied authentication is invalid"
        header[`WWW-Authenticate`] shouldEqual Some(`WWW-Authenticate`(basicChallenge))
      }
    }
    "reject requests with illegal Authorization header with 401" in {
      Get() ~> RawHeader("Authorization", "bob alice") ~> Route.seal {
        dontBasicAuth { echoComplete }
      } ~> check {
        status shouldEqual StatusCodes.Unauthorized
        responseAs[String] shouldEqual "The resource requires authentication, which was not supplied with the request"
        header[`WWW-Authenticate`] shouldEqual Some(`WWW-Authenticate`(basicChallenge))
      }
    }
    "extract the object representing the user identity created by successful authentication" in {
      Get() ~> Authorization(BasicHttpCredentials("Alice", "")) ~> {
        doBasicAuth { echoComplete }
      } ~> check { responseAs[String] shouldEqual "Alice" }
    }
    "extract the object representing the user identity created for the anonymous user" in {
      Get() ~> {
        anonBasicAuth { echoComplete }
      } ~> check { responseAs[String] shouldEqual "We are Legion" }
    }
    "properly handle exceptions thrown in its inner route" in {
      object TestException extends RuntimeException("Boom")
      EventFilter[TestException.type](
        occurrences = 1,
        start = "Error during processing of request: 'Boom'. Completing with 500 Internal Server Error response."
      ).intercept {
        Get() ~> Authorization(BasicHttpCredentials("Alice", "")) ~> {
          Route.seal {
            doBasicAuth { _ ⇒ throw TestException }
          }
        } ~> check { status shouldEqual StatusCodes.InternalServerError }
      }
    }
  }
  "bearer token authentication" should {
    "reject requests without Authorization header with an AuthenticationFailedRejection" in {
      Get() ~> {
        dontOAuth2Auth { echoComplete }
      } ~> check { rejection shouldEqual AuthenticationFailedRejection(CredentialsMissing, oAuth2Challenge) }
    }
    "reject unauthenticated requests with Authorization header with an AuthenticationFailedRejection" in {
      Get() ~> Authorization(OAuth2BearerToken("myToken")) ~> {
        dontOAuth2Auth { echoComplete }
      } ~> check { rejection shouldEqual AuthenticationFailedRejection(CredentialsRejected, oAuth2Challenge) }
    }
    "reject unauthenticated requests without Authorization header but with access_token URI parameter with an AuthenticationFailedRejection" in {
      Get("?access_token=myToken") ~> {
        dontOAuth2Auth { echoComplete }
      } ~> check { rejection shouldEqual AuthenticationFailedRejection(CredentialsRejected, oAuth2Challenge) }
    }
    "reject requests with an OAuth2 Bearer Token Authorization header with 401" in {
      Get() ~> Authorization(OAuth2BearerToken("myToken")) ~> Route.seal {
        dontOAuth2Auth { echoComplete }
      } ~> check {
        status shouldEqual StatusCodes.Unauthorized
        responseAs[String] shouldEqual "The supplied authentication is invalid"
        header[`WWW-Authenticate`] shouldEqual Some(`WWW-Authenticate`(oAuth2Challenge))
      }
    }
    "reject requests with illegal Authorization header with 401" in {
      Get() ~> RawHeader("Authorization", "bob alice") ~> Route.seal {
        dontOAuth2Auth { echoComplete }
      } ~> check {
        status shouldEqual StatusCodes.Unauthorized
        responseAs[String] shouldEqual "The resource requires authentication, which was not supplied with the request"
        header[`WWW-Authenticate`] shouldEqual Some(`WWW-Authenticate`(oAuth2Challenge))
      }
    }
    "extract the object representing the user identity created by successful authentication with Authorization header" in {
      Get() ~> Authorization(OAuth2BearerToken("myToken")) ~> {
        doOAuth2Auth { echoComplete }
      } ~> check { responseAs[String] shouldEqual "myToken" }
    }
    "extract the object representing the user identity created by successful authentication with access_token URI parameter" in {
      Get("?access_token=myToken") ~> {
        doOAuth2Auth { echoComplete }
      } ~> check { responseAs[String] shouldEqual "myToken" }
    }
    "extract the object representing the user identity created for the anonymous user" in {
      Get() ~> {
        anonBasicAuth { echoComplete }
      } ~> check { responseAs[String] shouldEqual "We are Legion" }
    }
    "properly handle exceptions thrown in its inner route" in {
      object TestException extends RuntimeException("Boom")
      EventFilter[TestException.type](
        occurrences = 1,
        start = "Error during processing of request: 'Boom'. Completing with 500 Internal Server Error response."
      ).intercept {
        Get() ~> Authorization(OAuth2BearerToken("myToken")) ~> {
          Route.seal {
            doOAuth2Auth { _ ⇒ throw TestException }
          }
        } ~> check { status shouldEqual StatusCodes.InternalServerError }
      }
    }
  }
  "authentication directives" should {
    "properly stack" in {
      val otherChallenge = HttpChallenge("MyAuth", Some("MyRealm2"))
      val otherAuth: Directive1[String] = authenticateOrRejectWithChallenge { (cred: Option[HttpCredentials]) ⇒
        Future.successful(Left(otherChallenge))
      }
      val bothAuth = dontBasicAuth | otherAuth

      Get() ~> Route.seal(bothAuth { echoComplete }) ~> check {
        status shouldEqual StatusCodes.Unauthorized
        headers.collect {
          case `WWW-Authenticate`(challenge +: Nil) ⇒ challenge
        } shouldEqual Seq(basicChallenge, otherChallenge)
      }
    }
  }

  "authorization directives" should {
    "authorize" in {
      Get() ~> {
        authorize(_ ⇒ true) { complete("OK") }
      } ~> check { responseAs[String] shouldEqual "OK" }
    }
    "not authorize" in {
      Get() ~> {
        authorize(_ ⇒ false) { complete("OK") }
      } ~> check { rejection shouldEqual AuthorizationFailedRejection }
    }

    "authorizeAsync" in {
      Get() ~> {
        authorizeAsync(_ ⇒ Future.successful(true)) { complete("OK") }
      } ~> check { responseAs[String] shouldEqual "OK" }
    }
    "not authorizeAsync" in {
      Get() ~> {
        authorizeAsync(_ ⇒ Future.successful(false)) { complete("OK") }
      } ~> check { rejection shouldEqual AuthorizationFailedRejection }
    }
    "not authorizeAsync when future fails" in {
      Get() ~> {
        authorizeAsync(_ ⇒ Future.failed(new Exception("Boom!"))) { complete("OK") }
      } ~> check { rejection shouldEqual AuthorizationFailedRejection }
    }
  }
  "basic authentication in realm" should {
    "reject requests without Authorization header with an AuthenticationFailedRejection" in {
      Get() ~> {
        dontBasicInRealmAuth("MyRealm") { echoComplete }
      } ~> check { rejection shouldEqual AuthenticationFailedRejection(CredentialsMissing, basicChallenge) }
    }
    "reject unauthenticated requests with Authorization header with an AuthenticationFailedRejection" in {
      Get() ~> Authorization(BasicHttpCredentials("Bob", "")) ~> {
        dontBasicInRealmAuth("MyRealm") { echoComplete }
      } ~> check { rejection shouldEqual AuthenticationFailedRejection(CredentialsRejected, basicChallenge) }
    }
    "reject unauthenticated requests for mismatched realm with Authorization header with an AuthenticationFailedRejection" in {
      Get() ~> Authorization(BasicHttpCredentials("Bob", "")) ~> {
        dontBasicInRealmAuth("MyRealm2") { echoComplete }
      } ~> check { rejection shouldEqual AuthenticationFailedRejection(CredentialsRejected, basicChallenge.copy(realm = "MyRealm")) }
    }
    "reject requests with a Basic Authorization header with 401" in {
      Get() ~> Authorization(BasicHttpCredentials("Alice", "")) ~> Route.seal {
        dontBasicInRealmAuth("MyRealm") { echoComplete }
      } ~> check {
        status shouldEqual StatusCodes.Unauthorized
        responseAs[String] shouldEqual "The supplied authentication is invalid"
        header[`WWW-Authenticate`] shouldEqual Some(`WWW-Authenticate`(basicChallenge))
      }
    }
    "reject requests with illegal Authorization header with 401" in {
      Get() ~> RawHeader("Authorization", "bob alice") ~> Route.seal {
        dontBasicInRealmAuth("MyRealm2") { echoComplete }
      } ~> check {
        status shouldEqual StatusCodes.Unauthorized
        responseAs[String] shouldEqual "The resource requires authentication, which was not supplied with the request"
        header[`WWW-Authenticate`] shouldEqual Some(`WWW-Authenticate`(basicChallenge))
      }
    }
    "extract the object representing the user identity created by successful authentication" in {
      Get() ~> Authorization(BasicHttpCredentials("Alice", "")) ~> {
        doBasicInRealmAuth("MyRealm") { echoComplete }
      } ~> check { responseAs[String] shouldEqual "Alice" }
    }
    "extract the object representing the user identity created for the anonymous user" in {
      Get() ~> {
        anonBasicInRealmAuth("MyRealm") { echoComplete }
      } ~> check { responseAs[String] shouldEqual "We are Legion" }
    }
    "properly handle exceptions thrown in its inner route" in {
      object TestException extends RuntimeException("Boom")
      EventFilter[TestException.type](
        occurrences = 1,
        start = "Error during processing of request: 'Boom'. Completing with 500 Internal Server Error response."
      ).intercept {
        Get() ~> Authorization(BasicHttpCredentials("Alice", "")) ~> {
          Route.seal {
            doBasicInRealmAuth("MyRealm") { _ ⇒ throw TestException }
          }
        } ~> check { status shouldEqual StatusCodes.InternalServerError }
      }
    }
  }
  "bearer token authentication in realm" should {
    "reject requests without Authorization header with an AuthenticationFailedRejection" in {
      Get() ~> {
        dontOAuth2InRealmAuth("MyRealm") { echoComplete }
      } ~> check { rejection shouldEqual AuthenticationFailedRejection(CredentialsMissing, oAuth2Challenge) }
    }
    "reject unauthenticated requests for mismatched realm with Authorization header with an AuthenticationFailedRejection" in {
      Get() ~> Authorization(OAuth2BearerToken("myToken")) ~> {
        dontOAuth2InRealmAuth("MyRealm2") { echoComplete }
      } ~> check { rejection shouldEqual AuthenticationFailedRejection(CredentialsRejected, oAuth2Challenge.copy(realm = "MyRealm")) }
    }
    "reject unauthenticated requests with Authorization header with an AuthenticationFailedRejection" in {
      Get() ~> Authorization(OAuth2BearerToken("myToken")) ~> {
        dontOAuth2InRealmAuth("MyRealm") { echoComplete }
      } ~> check { rejection shouldEqual AuthenticationFailedRejection(CredentialsRejected, oAuth2Challenge) }
    }
    "reject unauthenticated requests without Authorization header but with access_token URI parameter with an AuthenticationFailedRejection" in {
      Get("?access_token=myToken") ~> {
        dontOAuth2InRealmAuth("MyRealm") { echoComplete }
      } ~> check { rejection shouldEqual AuthenticationFailedRejection(CredentialsRejected, oAuth2Challenge) }
    }
    "reject requests with an OAuth2 Bearer Token Authorization header with 401" in {
      Get() ~> Authorization(OAuth2BearerToken("myToken")) ~> Route.seal {
        dontOAuth2InRealmAuth("MyRealm") { echoComplete }
      } ~> check {
        status shouldEqual StatusCodes.Unauthorized
        responseAs[String] shouldEqual "The supplied authentication is invalid"
        header[`WWW-Authenticate`] shouldEqual Some(`WWW-Authenticate`(oAuth2Challenge))
      }
    }
    "reject requests with illegal Authorization header with 401" in {
      Get() ~> RawHeader("Authorization", "bob alice") ~> Route.seal {
        dontOAuth2InRealmAuth("MyRealm") { echoComplete }
      } ~> check {
        status shouldEqual StatusCodes.Unauthorized
        responseAs[String] shouldEqual "The resource requires authentication, which was not supplied with the request"
        header[`WWW-Authenticate`] shouldEqual Some(`WWW-Authenticate`(oAuth2Challenge))
      }
    }
    "extract the object representing the user identity created by successful authentication with Authorization header" in {
      Get() ~> Authorization(OAuth2BearerToken("myToken")) ~> {
        doOAuth2InRealmAuth("MyRealm") { echoComplete }
      } ~> check { responseAs[String] shouldEqual "myToken" }
    }
    "extract the object representing the user identity created by successful authentication with access_token URI parameter" in {
      Get("?access_token=myToken") ~> {
        doOAuth2InRealmAuth("MyRealm") { echoComplete }
      } ~> check { responseAs[String] shouldEqual "myToken" }
    }
    "extract the object representing the user identity created for the anonymous user" in {
      Get() ~> {
        anonBasicInRealmAuth("MyRealm") { echoComplete }
      } ~> check { responseAs[String] shouldEqual "We are Legion" }
    }
    "properly handle exceptions thrown in its inner route" in {
      object TestException extends RuntimeException("Boom")
      EventFilter[TestException.type](
        occurrences = 1,
        start = "Error during processing of request: 'Boom'. Completing with 500 Internal Server Error response."
      ).intercept {
        Get() ~> Authorization(OAuth2BearerToken("myToken")) ~> {
          Route.seal {
            doOAuth2InRealmAuth("MyRealm") { _ ⇒ throw TestException }
          }
        } ~> check { status shouldEqual StatusCodes.InternalServerError }
      }
    }
  }
}
