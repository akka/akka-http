/*
 * Copyright (C) 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.jwt.scaladsl.server.directives

import akka.http.jwt.internal.{ JwtClaimsImpl, JwtSprayJson }
import akka.http.jwt.scaladsl.JwtSettings
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.server.AuthenticationFailedRejection.CredentialsRejected
import akka.http.scaladsl.server.{ AuthenticationFailedRejection, AuthorizationFailedRejection, Directives, Route }
import akka.http.scaladsl.testkit.ScalatestRouteTest
import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import spray.json.{ JsArray, JsBoolean, JsNumber, JsObject, JsString, JsValue }

import java.io.File
import java.util.Base64

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

  def configTemplate(secret: String) = s"""
          akka.loglevel = DEBUG
          akka.http.jwt {
            dev = off
            realm = my-realm
            secrets: [
              $secret
            ]
          }
          """

  val credentialsRejected = AuthenticationFailedRejection(CredentialsRejected, HttpChallenges.oAuth2("my-realm"))

  "The jwt() directive" should {

    def route(): Route =
      jwt() { claims =>
        complete(claims.asInstanceOf[JwtClaimsImpl].claims.toString())
      }

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

    "reject the request if the bearer token is expired even when dev mod is on" in {

      val devModeSettings = "akka.http.jwt.dev = on"
      val config = ConfigFactory.parseString(devModeSettings).withFallback(ConfigFactory.load())
      val expired = basicClaims + ("exp" -> JsNumber(1516239022 - 1))

      val route = jwt(settings = JwtSettings.apply(config)) { _ => complete("ok") }

      Get() ~> addHeader(jwtHeader(expired)) ~> route ~> check {
        rejection shouldEqual AuthenticationFailedRejection(CredentialsRejected, HttpChallenges.oAuth2("akka-http-jwt"))
      }
    }
  }

  "The extracted JwtClaims from jwt() directive" should {

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

    "allow extraction of a raw claim" in {
      val complexClaim = JsObject("id" -> JsString("abc"), "amount" -> JsNumber(12))
      Get() ~> addHeader(jwtHeader(basicClaims + ("extra" -> complexClaim))) ~> {
        jwt() {
          _.rawClaim("extra") match {
            case Some(f: JsValue) => complete(f.asJsObject.fields("id").toString())
          }
        }
      } ~> check {
        responseAs[String] shouldEqual "\"abc\"" // rawClaim returns the raw JSON value
      }
    }

    "allow extraction of a list of string claim values" in {
      Get() ~> addHeader(jwtHeader(basicClaims + ("roles" -> JsArray(JsString("read"), JsString("write"))))) ~> {
        jwt() {
          _.stringClaims("roles") match {
            case elems if elems.contains("read") => complete("ok")
            case _                               => reject(AuthorizationFailedRejection)
          }
        }
      } ~> check {
        responseAs[String] shouldEqual "ok"
      }
    }

    "allow for checking the value of the required claim" in {
      Get() ~> addHeader(jwtHeader(basicClaims)) ~> {
        jwt() {
          _.stringClaim("role") match {
            case Some("admin") => complete(HttpResponse())
            case _             => reject(AuthorizationFailedRejection)
          }
        }
      } ~> check {
        rejection shouldEqual AuthorizationFailedRejection
      }
    }

    "validate JWTs using asymmetric keys" in {
      val asymmetricSecret = configTemplate(
        s"""
             {
               key-id: asymmetric-key
               issuer: my-issuer
               algorithm: RS256
               public-key: "${getClass.getClassLoader.getResource("my-public.key").getPath}"
             }
          """)

      val config = ConfigFactory.parseString(asymmetricSecret).withFallback(ConfigFactory.load())
      val route =
        jwt(settings = JwtSettings.apply(config)) { claims: JwtClaims =>
          complete(s"${claims.stringClaim("sub").get}:${claims.stringClaim("name").get}")
        }

      val jwtToken = Authorization(OAuth2BearerToken(
        read(getClass.getClassLoader.getResource("my-jwt-token.txt").getPath)
      ))
      Get() ~> addHeader(jwtToken) ~> route ~> check {
        responseAs[String] shouldBe "1234567890:John Doe"
      }
    }

    "reject when asymmetric secret is not properly configured" in {
      {
        val asymmetricUsingSecret = configTemplate(
          s"""
             {
               key-id: asymmetric-key
               issuer: my-issuer
               algorithm: RS256
               secret: "something"
             }
          """)

        val wrongConfig = ConfigFactory.parseString(asymmetricUsingSecret).withFallback(ConfigFactory.load())

        intercept[IllegalArgumentException] {
          Get() ~> jwt(settings = JwtSettings.apply(wrongConfig)) { _ => complete("ok") }
        }.getMessage should include("Secret literal for key id [asymmetric-key] not supported with asymmetric algorithms")
      }

      {
        val asymmetricWithoutPublicKey = configTemplate(
          s"""
             {
               key-id: asymmetric-key
               issuer: my-issuer
               algorithm: RS256
             }
          """)

        val wrongConfig = ConfigFactory.parseString(asymmetricWithoutPublicKey).withFallback(ConfigFactory.load())
        intercept[IllegalArgumentException] {
          Get() ~> jwt(settings = JwtSettings.apply(wrongConfig)) { _ => complete("ok") }
        }.getMessage should include("Depending on the used algorithm, a secret or a public key must be configured.")
      }
    }


    "ignore signature if using dev mode" in {
      val devModeSettings = "akka.http.jwt.dev = on"
      val config = ConfigFactory.parseString(devModeSettings).withFallback(ConfigFactory.load())

      val route =
        jwt(settings = JwtSettings.apply(config)) {
          _.stringClaim("sub") match {
            case Some(sub) => complete(sub)
            case None      => reject(AuthorizationFailedRejection)
          }
        }

      // removing the signature part of the JWT token, this makes it invalid unless dev mode is on
      val jwtTokenNoSignature =
        read(getClass.getClassLoader.getResource("my-jwt-token.txt").getPath).split('.').take(2).mkString(".")

      val header = Authorization(OAuth2BearerToken(jwtTokenNoSignature))
      Get() ~> addHeader(header) ~> route ~> check {
        responseAs[String] shouldBe "1234567890"
      }
    }

  }

  private def read(filePath: String): String = {
    val source = scala.io.Source.fromFile(new File(filePath), "UTF-8")
    try {
      source.mkString
    } finally {
      source.close()
    }
  }
}
