/*
 * Copyright (C) 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.server.directives

import akka.http.jwt.impl.settings.JwtSprayJson
import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.server.AuthenticationFailedRejection.CredentialsRejected
import akka.http.scaladsl.server.StandardRoute.toDirective
import akka.http.scaladsl.server.directives.RouteDirectives.reject
import akka.http.scaladsl.server.{AuthenticationFailedRejection, Directives, MalformedQueryParamRejection, MissingQueryParamRejection, Route}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.testkit._
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.Inside.inside
import org.scalatest.Suite
import org.scalatest.concurrent.ScalaFutures
import spray.json.{JsBoolean, JsNumber, JsObject, JsString, JsValue, enrichAny}
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.{Base64, NoSuchElementException}
import scala.util.Success

class JwtDirectivesSpec extends AnyWordSpec with ScalatestRouteTest with JwtDirectives with Directives with Matchers {

  def secret = "akka is great"

  override def testConfigSource =
    s"""
       akka.loglevel = DEBUG
       akka.http.jwt {
         dev = off
         realm = my-realm
         secrets: [
           {
             key-id: my-key
             issuer: my-issuer
             algorithm: HS256
             secret: "${Base64.getEncoder.encodeToString(secret.getBytes)}"
           },
           {
             key-id: other-key
             issuer: my-secondary-issuer
             algorithm: HS256
             secret: "${Base64.getEncoder.encodeToString("akka is better than great".getBytes)}"
           }
         ]
       }
      """

  val basicClaims = Map[String, JsValue](
    "sub" -> JsString("1234567890"),
    "name" -> JsString("John Doe"),
    "iat" -> JsNumber(1516239022))

  def jwtHeader(claims: Map[String, JsValue] = basicClaims, secret: String = secret): Authorization = {
    val token = JwtSprayJson.encode(JsObject("alg" -> JsString("HS256")), JsObject(claims), secret)
    Authorization(OAuth2BearerToken(token))
  }

  def route(): Route =
    jwt() { claims: JwtClaims =>
      complete(claims.toJson)
    }

  val orderGetOrPutWithMethod =
    path("order" / IntNumber) & (get | put) & extractMethod

  val credentialsRejected = AuthenticationFailedRejection(CredentialsRejected, HttpChallenges.oAuth2("my-realm"))

  "The jwt() directive" should {
    "extract the claims from a valid bearer token in the Authorization header" in {
      Get() ~> addHeader(jwtHeader(basicClaims)) ~> route() ~> check {
        responseAs[String] shouldBe """{"iat":1516239022,"name":"John Doe","sub":"1234567890"}"""
      }
    }

    "extract the claims from a valid bearer token with an issuer specified" in {
      Get() ~> addHeader(jwtHeader(basicClaims + ("iss" -> JsString("my-issuer")))) ~> route() ~> check {
        responseAs[String] shouldBe """{"iat":1516239022,"iss":"my-issuer","name":"John Doe","sub":"1234567890"}"""
      }

      Get() ~> addHeader(jwtHeader(basicClaims + ("iss" -> JsString("my-secondary-issuer")), secret = "akka is better than great")) ~> route() ~>
        check {
          responseAs[String] shouldBe """{"iat":1516239022,"iss":"my-secondary-issuer","name":"John Doe","sub":"1234567890"}"""
        }
    }

    "reject the request if the bearer token is expired" in {
      val expired = basicClaims + ("exp" -> JsNumber(1516239022 - 1))
      Get() ~> addHeader(jwtHeader(expired)) ~> route() ~> check {
        rejection shouldEqual credentialsRejected
      }
    }

    "reject the request if the bearer token is used before being valid" in {
      // notBefore is set to 60 seconds in the future
      val notBefore = basicClaims + ("nbf" -> JsNumber(System.currentTimeMillis() / 1000 + 60))
      Get() ~> addHeader(jwtHeader(notBefore)) ~> route() ~> check {
        rejection shouldEqual credentialsRejected
      }
    }

    "reject the request if the bearer token uses a wrong secret" in {
      val token = JwtSprayJson.encode(JsObject("alg" -> JsString("HS256")), JsObject(basicClaims), "wrong-secret")
      Get() ~> addHeader(Authorization(OAuth2BearerToken(token))) ~> route() ~> check {
        rejection shouldEqual credentialsRejected
      }
    }

    "reject the request if the bearer token has a different issuer than the secret configured" in {
      val difIssuer = basicClaims + ("iss" -> JsString("other-issuer"))
      Get() ~> addHeader(jwtHeader(difIssuer)) ~> route() ~> check {
        rejection shouldEqual credentialsRejected
      }
    }

    "reject the request if the bearer token has a different key-id that the secret configured" in {
      val token = JwtSprayJson.encode(JsObject("alg" -> JsString("HS256"), "kid" -> JsString("other-key")), JsObject(basicClaims), secret)
      Get() ~> addHeader(Authorization(OAuth2BearerToken(token))) ~> route() ~> check {
        rejection shouldEqual credentialsRejected
      }
    }
  }

  "The claim() directive" should {

    "allow for extracting claims with a specific type" in {
      val extraClaims = basicClaims + ("int" -> JsNumber(42)) + ("double" -> JsNumber(42.42)) + ("long" -> JsNumber(11111111111L)) + ("bool" -> JsBoolean(true))
      val routeWithTypedClaims =
        jwt() { claims: JwtClaims =>
          {
            val result = for {
              sub <- claims.stringClaim("sub")
              int <- claims.intClaim("int")
              long <- claims.longClaim("long")
              double <- claims.doubleClaim("double")
              bool <- claims.booleanClaim("bool")
            } yield s"$sub:$int:$long:$double:$bool"

            complete(result)
          }
        }

      Get() ~> addHeader(jwtHeader(extraClaims)) ~> routeWithTypedClaims ~> check {
        responseAs[String] shouldBe "1234567890:42:11111111111:42.42:true"
      }
    }

    "supply typed default values" in {

      Get() ~> addHeader(jwtHeader(basicClaims)) ~> {
        jwt() { claims =>
          val amount = claims.intClaim("amount").getOrElse(45)
          complete(amount.toString)
        }
      } ~> check {
        responseAs[String] shouldEqual "45"
      }
    }

    "create typed optional parameters that extract Some(value) when present" in {
      Get() ~> addHeader(jwtHeader(basicClaims + ("amount" -> JsNumber(12)))) ~> {
        jwt() { claims =>
          val amount = claims.intClaim("amount")
          complete(amount.toString)
        }
      } ~> check {
        responseAs[String] shouldEqual "Some(12)"
      }

      Get() ~> addHeader(jwtHeader(basicClaims + ("id" -> JsString("hello")))) ~> {
        jwt() { claims =>
          val id = claims.stringClaim("id")
          complete(id.toString)
        }
      } ~> check {
        responseAs[String] shouldEqual "Some(hello)"
      }
    }

    "create typed optional parameters that extract None when not present" in {
      Get() ~> addHeader(jwtHeader(basicClaims)) ~> {
        jwt() { claims =>
          val amount = claims.intClaim("amount")
          complete(amount.toString)
        }
      } ~> check {
        responseAs[String] shouldEqual "None"
      }
    }

    "allow for checking the value of the required claim" in {
      Get() ~> addHeader(jwtHeader(basicClaims)) ~> {
        jwt() { _.stringClaim("role") match {
            case Some("admin") => complete(HttpResponse())
            case _ => reject(MissingQueryParamRejection("role"))
          }
        }
      } ~> check {
        rejection shouldEqual MissingQueryParamRejection("role")
      }
    }
  }
}
