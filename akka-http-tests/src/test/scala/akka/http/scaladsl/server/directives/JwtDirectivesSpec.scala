/*
 * Copyright (C) 2024 Lightbend Inc. <https://www.lightbend.com>
 * Copyright 2016 Lomig Mégard
 */

package akka.http.scaladsl.server.directives

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.server.AuthenticationFailedRejection.CredentialsRejected
import akka.http.scaladsl.server.StandardRoute.toDirective
import akka.http.scaladsl.server.directives.RouteDirectives.reject
import akka.http.scaladsl.server.util.JwtSprayJson
import akka.http.scaladsl.server.{AuthenticationFailedRejection, Directive1, InvalidRequiredValueForQueryParamRejection, MalformedQueryParamRejection, MalformedRequestContentRejection, MissingQueryParamRejection, RejectionError, Route, RoutingSpec}
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.Inside.inside
import spray.json.{JsBoolean, JsNumber, JsObject, JsString, JsValue, enrichAny}
import org.scalatest.freespec.AnyFreeSpec

import java.util.{Base64, NoSuchElementException}
import scala.util.Success

// This test is based on the akka-http-cors project by Lomig Mégard, licensed under the Apache License, Version 2.0.
class JwtDirectivesSpec extends RoutingSpec {


  def secret = "akka is great"

  override def testConfigSource =
     s"""
       akka.http.jwt {
         dev = off
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
        rejection shouldEqual AuthenticationFailedRejection(CredentialsRejected, HttpChallenges.oAuth2("realm"))
      }
    }

    "reject the request if the bearer token is used before being valid" in {
      // notBefore is set to 60 seconds in the future
      val notBefore = basicClaims + ("nbf" -> JsNumber(System.currentTimeMillis() / 1000 + 60))
      Get() ~> addHeader(jwtHeader(notBefore)) ~> route() ~> check {
        rejection shouldEqual AuthenticationFailedRejection(CredentialsRejected, HttpChallenges.oAuth2("realm"))
      }
    }

    "reject the request if the bearer token uses a wrong secret" in {
      val token = JwtSprayJson.encode(JsObject("alg" -> JsString("HS256")), JsObject(basicClaims), "wrong-secret")
      Get() ~> addHeader(Authorization(OAuth2BearerToken(token))) ~> route() ~> check {
        rejection shouldEqual AuthenticationFailedRejection(CredentialsRejected, HttpChallenges.oAuth2("realm"))
      }
    }

    "reject the request if the bearer token has a different issuer than the secret configured" in {
      val difIssuer = basicClaims + ("iss" -> JsString("other-issuer"))
      Get() ~> addHeader(jwtHeader(difIssuer)) ~> route() ~> check {
        rejection shouldEqual AuthenticationFailedRejection(CredentialsRejected, HttpChallenges.oAuth2("realm"))
      }
    }

    "reject the request if the bearer token has a different key-id that the secret configured" in {
      val token = JwtSprayJson.encode(JsObject("alg" -> JsString("HS256"), "kid" -> JsString("other-key")), JsObject(basicClaims), secret)
      Get() ~> addHeader(Authorization(OAuth2BearerToken(token))) ~> route() ~> check {
        rejection shouldEqual AuthenticationFailedRejection(CredentialsRejected, HttpChallenges.oAuth2("realm"))
      }
    }
  }

  "The claim() directive" should {

    "allow for making claims required" in {
      val extraClaims = basicClaims + ("int" -> JsNumber(42)) + ("float" -> JsNumber(42.42)) + ("long" -> JsNumber(11111111111L)) + ("bool" -> JsBoolean(true))
      val routeWithTypedClaims =
        jwt() { claims: JwtClaims => {
            val result = for {
              sub <- claims.get[String]("sub")
              int <- claims.get[Int]("int")
              long <- claims.get[Long]("long")
              float <- claims.get[Float]("float")
              bool <- claims.get[Boolean]("bool")
            } yield s"$sub:$int:$long:$float:$bool"

            complete(result)
          }
        }

      Get() ~> addHeader(jwtHeader(extraClaims)) ~> routeWithTypedClaims ~> check {
        responseAs[String] shouldBe "1234567890:42:11111111111:42.42:true"
      }

      //FIXME: fail when sub claim is not passed
      //Get() ~> addHeader(jwtHeader(basicClaims - "sub")) ~> routeWithTypedClaims ~> check {
      //  inside(rejection) { case MissingQueryParamRejection("sub") => }
      //}
    }

    "supply typed default values" in {

      Get() ~> addHeader(jwtHeader(basicClaims)) ~> {
        jwt() { claims =>
          val amount = claims.get[Int]("amount", default = 45)
          onSuccess(amount) { v => complete(v.toString) }
        }
      } ~> check {
        responseAs[String] shouldEqual "45"
      }
    }

    "create typed optional parameters that extract Some(value) when present" in {
      Get() ~> addHeader(jwtHeader(basicClaims + ("amount" -> JsNumber(12)))) ~> {
        jwt() { claims =>
          val amount = claims.getOpt[Int]("amount")
          onSuccess(amount) { v => complete(v.toString) }
        }
      } ~> check {
        responseAs[String] shouldEqual "Some(12)"
      }

      Get() ~> addHeader(jwtHeader(basicClaims + ("id" -> JsString("hello")))) ~> {
        jwt() { claims =>
          val id = claims.getOpt[String]("id")
          onSuccess(id) { v => complete(v.toString) }
        }
      } ~> check {
        responseAs[String] shouldEqual "Some(hello)"
      }
    }

    "create typed optional parameters that extract None when not present" in {
      Get() ~> addHeader(jwtHeader(basicClaims)) ~> {
        jwt() { claims =>
          val amount = claims.getOpt[Int]("amount")
          onSuccess(amount) { v => complete(v.toString) }
        }
      } ~> check {
        responseAs[String] shouldEqual "None"
      }
    }

    "cause a MalformedQueryParamRejection on illegal Int values" in {
      Get() ~> addHeader(jwtHeader(basicClaims + ("amount" -> JsString("x")))) ~> {
        jwt() { claims =>
          val amount = claims.getOpt[Int]("amount")
          onSuccess(amount) { v => complete(v.toString) }
        }
      } ~> check {
        inside(rejection) {
          case MalformedQueryParamRejection("amount", "'x' is not a valid 32-bit signed integer value", Some(_)) =>
        }
      }
    }
/*
  "The claim() requirement directive" should {
    "reject the request with a MissingQueryParamRejection if jwt do not contain the required claim" in {
      Get() ~> addHeader(jwtHeader(basicClaims)) ~> {
        jwt() {
          claim("role".requiredValue("admin")) { _ => completeOk }
        }
      } ~> check { rejection shouldEqual MissingQueryParamRejection("role") }
    }
    "reject the request with a InvalidRequiredValueForQueryParamRejection if the required claim has an unmatching value" in {
      Get() ~> addHeader(jwtHeader(basicClaims + ("role" -> JsString("viewer")))) ~> {
        jwt() { implicit claims =>
          claim("role".requiredValue("admin")) { _ => completeOk }
        }
      } ~> check { rejection shouldEqual InvalidRequiredValueForQueryParamRejection("role", "admin", "viewer") }
    }
    "let requests pass that contain the required parameter with its required value" in {
      Get() ~> addHeader(jwtHeader(basicClaims + ("role" -> JsString("admin")))) ~> {
        jwt() { implicit claims =>
          claim("role".requiredValue("admin")) { _ => completeOk }
        }
      } ~> check { response shouldEqual Ok }
    }*/
  }
}
