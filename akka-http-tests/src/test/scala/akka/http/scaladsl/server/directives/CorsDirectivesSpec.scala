/*
 * Copyright (C) 2024 Lightbend Inc. <https://akka.io>
 * Copyright 2016 Lomig Mégard
 */

package akka.http.scaladsl.server.directives

import akka.http.impl.settings.HttpOriginMatcher
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.{ CorsRejection, Route, RoutingSpec }
import akka.http.scaladsl.settings.CorsSettings
import org.scalatest.Inspectors.forAll

// This test is based on the akka-http-cors project by Lomig Mégard, licensed under the Apache License, Version 2.0.
class CorsDirectivesSpec extends RoutingSpec {
  import HttpMethods._

  val actual = "actual"
  val exampleOrigin = HttpOrigin("http://example.com")
  val exampleStatus = StatusCodes.Created

  val referenceSettings = CorsSettings(system).withAllowCredentials(true)

  override def testConfigSource =
    """
      akka.http.cors.allow-credentials = off
      """

  def route(settings: CorsSettings, responseHeaders: Seq[HttpHeader] = Nil): Route =
    cors(settings) {
      complete(HttpResponse(exampleStatus, responseHeaders, HttpEntity(actual)))
    }

  "The cors() directive" should {
    "extract its settings from the actor system" in {
      val route = cors() {
        complete(HttpResponse(exampleStatus, Nil, HttpEntity(actual)))
      }

      Get() ~> Origin(exampleOrigin) ~> route ~> check {
        responseAs[String] shouldBe actual
        response.status shouldBe exampleStatus
        response.headers should contain theSameElementsAs Seq(
          `Access-Control-Allow-Origin`.* // no allow-credentials because above
        )
      }
    }
  }

  "The cors(settings) directive" should {
    "not affect actual requests when not strict" in {
      val settings = referenceSettings
      val responseHeaders = Seq(Host("my-host"), `Access-Control-Max-Age`(60))
      Get() ~> {
        route(settings, responseHeaders)
      } ~> check {
        responseAs[String] shouldBe actual
        response.status shouldBe exampleStatus
        // response headers should be untouched, including the CORS-related ones
        response.headers shouldBe responseHeaders
      }
    }

    "reject requests without Origin header when strict" in {
      val settings = referenceSettings.withAllowGenericHttpRequests(false)
      Get() ~> {
        route(settings)
      } ~> check {
        rejection shouldBe CorsRejection("malformed request")
      }
    }

    "accept actual requests with a single Origin" in {
      val settings = referenceSettings
      Get() ~> Origin(exampleOrigin) ~> {
        route(settings)
      } ~> check {
        responseAs[String] shouldBe actual
        response.status shouldBe exampleStatus
        response.headers should contain theSameElementsAs Seq(
          `Access-Control-Allow-Origin`(exampleOrigin),
          `Access-Control-Allow-Credentials`(true)
        )
      }
    }

    "flag credentials allowed when they are" in {
      val settings = referenceSettings.withAllowCredentials(true)
      Get() ~> Origin(exampleOrigin) ~> {
        route(settings)
      } ~> check {
        responseAs[String] shouldBe actual
        response.status shouldBe exampleStatus
        response.headers should contain theSameElementsAs Seq(
          `Access-Control-Allow-Origin`(exampleOrigin.toString),
          `Access-Control-Allow-Credentials`(true)
        )
      }
    }

    "accept pre-flight requests with a null origin when allowed-origins = `*`" in {
      val settings = referenceSettings
      Options() ~> Origin(Seq.empty) ~> `Access-Control-Request-Method`(GET) ~> {
        route(settings)
      } ~> check {
        status shouldBe StatusCodes.OK
        response.headers should contain theSameElementsAs Seq(
          `Access-Control-Allow-Origin`.`null`,
          `Access-Control-Allow-Methods`(settings.allowedMethods.toArray),
          `Access-Control-Max-Age`(1800),
          `Access-Control-Allow-Credentials`(true)
        )
      }
    }

    "reject pre-flight requests with a null origin when allowed-origins != `*`" in {
      val settings = referenceSettings.withAllowedOrigins(Set(exampleOrigin.toString))
      Options() ~> Origin(Seq.empty) ~> `Access-Control-Request-Method`(GET) ~> {
        route(settings)
      } ~> check {
        rejection shouldBe CorsRejection("invalid origin 'null'")
      }
    }

    "accept actual requests with a null Origin" in {
      val settings = referenceSettings
      Get() ~> Origin(Seq.empty) ~> {
        route(settings)
      } ~> check {
        responseAs[String] shouldBe actual
        response.status shouldBe exampleStatus
        response.headers should contain theSameElementsAs Seq(
          `Access-Control-Allow-Origin`.`null`,
          `Access-Control-Allow-Credentials`(true)
        )
      }
    }

    "accept actual requests with an Origin matching an allowed subdomain" in {
      val subdomainOrigin = HttpOrigin("http://sub.example.com")

      val settings = referenceSettings.withAllowedOrigins(Set("http://*.example.com"))
      Get() ~> Origin(subdomainOrigin) ~> {
        route(settings)
      } ~> check {
        responseAs[String] shouldBe actual
        response.status shouldBe exampleStatus
        response.headers should contain theSameElementsAs Seq(
          `Access-Control-Allow-Origin`(subdomainOrigin),
          `Access-Control-Allow-Credentials`(true)
        )
      }
    }

    "return `Access-Control-Allow-Origin: *` to actual request only when credentials are not allowed" in {
      val settings = referenceSettings.withAllowCredentials(false)
      Get() ~> Origin(exampleOrigin) ~> {
        route(settings)
      } ~> check {
        responseAs[String] shouldBe actual
        response.status shouldBe exampleStatus
        response.headers shouldBe Seq(
          `Access-Control-Allow-Origin`.*
        )
      }
    }

    "return `Access-Control-Expose-Headers` to actual request with all the exposed headers in the settings" in {
      val exposedHeaders = Set("X-a", "X-b", "X-c")
      val settings = referenceSettings.withExposedHeaders(exposedHeaders)
      Get() ~> Origin(exampleOrigin) ~> {
        route(settings)
      } ~> check {
        responseAs[String] shouldBe actual
        response.status shouldBe exampleStatus
        response.headers shouldBe Seq(
          `Access-Control-Allow-Origin`(exampleOrigin),
          `Access-Control-Expose-Headers`(exposedHeaders.toArray),
          `Access-Control-Allow-Credentials`(true)
        )
      }
    }

    "remove CORS-related headers from the original response before adding the new ones" in {
      val settings = referenceSettings.withExposedHeaders(Set("X-good"))
      val responseHeaders = Seq(
        Host("my-host"), // untouched
        `Access-Control-Allow-Origin`("http://bad.com"), // replaced
        `Access-Control-Expose-Headers`("X-bad"), // replaced
        `Access-Control-Allow-Credentials`(false), // replaced
        `Access-Control-Allow-Methods`(HttpMethods.POST), // removed
        `Access-Control-Allow-Headers`("X-bad"), // removed
        `Access-Control-Max-Age`(60) // removed
      )
      Get() ~> Origin(exampleOrigin) ~> {
        route(settings, responseHeaders)
      } ~> check {
        responseAs[String] shouldBe actual
        response.status shouldBe exampleStatus
        response.headers should contain theSameElementsAs Seq(
          `Access-Control-Allow-Origin`(exampleOrigin),
          `Access-Control-Expose-Headers`("X-good"),
          `Access-Control-Allow-Credentials`(true),
          Host("my-host")
        )
      }
    }

    "accept valid pre-flight requests" in {
      val settings = referenceSettings
      Options() ~> Origin(exampleOrigin) ~> `Access-Control-Request-Method`(GET) ~> {
        route(settings)
      } ~> check {
        response.entity shouldBe HttpEntity.Empty
        status shouldBe StatusCodes.OK
        response.headers should contain theSameElementsAs Seq(
          `Access-Control-Allow-Origin`(exampleOrigin),
          `Access-Control-Allow-Methods`(settings.allowedMethods.toArray),
          `Access-Control-Max-Age`(1800),
          `Access-Control-Allow-Credentials`(true)
        )
      }
    }

    "accept actual requests with OPTION method" in {
      val settings = referenceSettings
      Options() ~> Origin(exampleOrigin) ~> {
        route(settings)
      } ~> check {
        responseAs[String] shouldBe actual
        response.status shouldBe exampleStatus
        response.headers should contain theSameElementsAs Seq(
          `Access-Control-Allow-Origin`(exampleOrigin),
          `Access-Control-Allow-Credentials`(true)
        )
      }
    }

    "reject actual requests with invalid origin" when {
      "the origin is null" in {
        val settings = referenceSettings.withAllowedOrigins(Set(exampleOrigin.toString))
        Get() ~> Origin(Seq.empty) ~> {
          route(settings)
        } ~> check {
          rejection shouldBe CorsRejection("invalid origin 'null'")
        }
      }
      "there is one origin" in {
        val settings = referenceSettings.withAllowedOrigins(Set(exampleOrigin.toString))
        val invalidOrigin = HttpOrigin("http://invalid.com")
        Get() ~> Origin(invalidOrigin) ~> {
          route(settings)
        } ~> check {
          rejection shouldBe CorsRejection(s"invalid origin '${invalidOrigin.toString}'")
        }
      }
    }

    "reject pre-flight requests with invalid origin" in {
      val settings = referenceSettings.withAllowedOrigins(Set(exampleOrigin.toString))
      val invalidOrigin = HttpOrigin("http://invalid.com")
      Options() ~> Origin(invalidOrigin) ~> `Access-Control-Request-Method`(GET) ~> {
        route(settings)
      } ~> check {
        rejection shouldBe CorsRejection(s"invalid origin '${invalidOrigin.toString}'")
      }
    }

    "reject pre-flight requests with invalid method" in {
      val settings = referenceSettings
      val invalidMethod = HttpMethods.PATCH
      Options() ~> Origin(exampleOrigin) ~> `Access-Control-Request-Method`(invalidMethod) ~> {
        route(settings)
      } ~> check {
        rejection shouldBe CorsRejection("invalid method 'PATCH'")
      }
    }

    "reject pre-flight requests with invalid header" in {
      val settings = referenceSettings.withAllowedHeaders(Set[String]())
      val invalidHeader = "X-header"
      Options() ~> Origin(exampleOrigin) ~> `Access-Control-Request-Method`(HttpMethods.GET) ~>
        `Access-Control-Request-Headers`(invalidHeader) ~> {
          route(settings)
        } ~> check {
          rejection shouldBe CorsRejection("invalid headers 'X-header'")
        }
    }

    "reject pre-flight requests with multiple origins" in {
      val settings = referenceSettings.withAllowGenericHttpRequests(false)
      Options() ~> Origin(exampleOrigin, exampleOrigin) ~> `Access-Control-Request-Method`(GET) ~> {
        route(settings)
      } ~> check {
        rejection shouldBe CorsRejection("malformed request")
      }
    }
  }

  "The default rejection handler" should {
    val settings = referenceSettings
      .withAllowGenericHttpRequests(false)
      .withAllowedOrigins(Set(exampleOrigin.toString))
      .withAllowedHeaders(Set[String]())
    val sealedRoute = Route.seal(route(settings))

    "handle the malformed request cause" in {
      Get() ~> {
        sealedRoute
      } ~> check {
        status shouldBe StatusCodes.BadRequest
        entityAs[String] shouldBe "CORS: malformed request"
      }
    }

    "handle a request with invalid origin" when {
      "the origin is null" in {
        Get() ~> Origin(Seq.empty) ~> {
          sealedRoute
        } ~> check {
          status shouldBe StatusCodes.BadRequest
          entityAs[String] shouldBe s"CORS: invalid origin 'null'"
        }
      }
      "there is one origin" in {
        Get() ~> Origin(HttpOrigin("http://invalid.com")) ~> {
          sealedRoute
        } ~> check {
          status shouldBe StatusCodes.BadRequest
          entityAs[String] shouldBe s"CORS: invalid origin 'http://invalid.com'"
        }
      }
      "there are two origins" in {
        Get() ~> Origin(HttpOrigin("http://invalid1.com"), HttpOrigin("http://invalid2.com")) ~> {
          sealedRoute
        } ~> check {
          status shouldBe StatusCodes.BadRequest
          entityAs[String] shouldBe s"CORS: invalid origin 'http://invalid1.com http://invalid2.com'"
        }
      }
    }

    "handle a pre-flight request with invalid method" in {
      Options() ~> Origin(exampleOrigin) ~> `Access-Control-Request-Method`(PATCH) ~> {
        sealedRoute
      } ~> check {
        status shouldBe StatusCodes.BadRequest
        entityAs[String] shouldBe s"CORS: invalid method 'PATCH'"
      }
    }

    "handle a pre-flight request with invalid headers" in {
      Options() ~> Origin(exampleOrigin) ~> `Access-Control-Request-Method`(GET) ~>
        `Access-Control-Request-Headers`("X-a", "X-b") ~> {
          sealedRoute
        } ~> check {
          status shouldBe StatusCodes.BadRequest
          entityAs[String] shouldBe s"CORS: invalid headers 'X-a X-b'"
        }
    }

    "handle multiple CORS rejections" in {
      Options() ~> Origin(HttpOrigin("http://invalid.com")) ~> `Access-Control-Request-Method`(PATCH) ~>
        `Access-Control-Request-Headers`("X-a", "X-b") ~> {
          sealedRoute
        } ~> check {
          status shouldBe StatusCodes.BadRequest
          entityAs[String] shouldBe
            s"CORS: invalid origin 'http://invalid.com', invalid method 'PATCH', invalid headers 'X-a X-b'"
        }
    }
  }

  "The CORS origin matcher" should {
    "match any origin with *" in {
      val origins = Seq(
        "http://localhost",
        "http://192.168.1.1",
        "http://test.com",
        "http://test.com:8080",
        "https://test.com",
        "https://test.com:4433"
      ).map(HttpOrigin.apply)

      forAll(origins) { o => HttpOriginMatcher.matchAny.apply(Seq(o)) shouldBe true }
      HttpOriginMatcher.matchAny(origins) shouldBe true
    }

    "match exact origins" in {
      val positives = Seq(
        "http://localhost",
        "http://test.com",
        "https://test.ch:12345"
      ).map(HttpOrigin.apply)

      val negatives = Seq(
        "http://localhost:80",
        "https://localhost",
        "http://test.com:8080",
        "https://test.ch",
        "https://abc.test.uk.co",
      ).map(HttpOrigin.apply)

      val matcher = HttpOriginMatcher.apply(Set(
        "http://localhost",
        "http://test.com",
        "https://test.ch:12345"
      ))

      forAll(positives) { o => matcher.apply(Seq(o)) shouldBe true }

      forAll(negatives) { o => matcher.apply(Seq(o)) shouldBe false }

      matcher(positives) shouldBe true
      matcher(negatives) shouldBe false
      matcher(negatives ++ positives) shouldBe true // at least one match
      matcher(positives ++ negatives) shouldBe true // at least one match
    }

    "match sub-domains with wildcards" in {
      val matcher = HttpOriginMatcher(
        Set(
          "http://test.com",
          "https://test.ch:12345",
          "https://*.test.uk.co",
          "http://*.abc.com:8080",
          "http://*abc.com", // Must start with `*.`
          "http://abc.*.middle.com" // The wildcard can't be in the middle
        )
      )

      val positives = Seq(
        "http://test.com",
        "https://test.ch:12345",
        "https://sub.test.uk.co",
        "https://sub1.sub2.test.uk.co",
        "http://sub.abc.com:8080"
      ).map(HttpOrigin.apply)

      val negatives = Seq(
        "http://test.com:8080",
        "http://sub.test.uk.co", // must compare the scheme
        "http://sub.abc.com", // must compare the port
        "http://abc.test.com", // no wildcard
        "http://sub.abc.com",
        "http://subabc.com",
        "http://abc.sub.middle.com",
        "http://abc.middle.com"
      ).map(HttpOrigin.apply)

      forAll(positives) { o => matcher.apply(Seq(o)) shouldBe true }

      forAll(negatives) { o => matcher.apply(Seq(o)) shouldBe false }
      matcher(negatives ++ positives) shouldBe true
      matcher(positives ++ negatives) shouldBe true
    }
  }

}
