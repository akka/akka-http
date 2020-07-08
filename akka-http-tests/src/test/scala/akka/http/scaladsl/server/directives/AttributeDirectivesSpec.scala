/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.server.directives

import akka.http.scaladsl.model._
import akka.http.scaladsl.server._

class AttributeDirectivesSpec extends RoutingSpec {
  "The attribute directive" should {
    val key = AttributeKey[String]("test-key")
    val route = attribute(key) { value =>
      complete(s"The attribute value was [$value]")
    }

    "extract the respective attribute value if a matching attribute is present" in {
      Get("/abc") ~> addAttribute(key, "the-value") ~> route ~> check {
        responseAs[String] shouldEqual "The attribute value was [the-value]"
      }
    }

    "reject if no matching request attribute is present" in {
      Get("/abc") ~> route ~> check {
        rejection shouldBe MissingAttributeRejection(key)
      }
    }

    "reject a request if no header of the given type is present" in {
      Get("abc") ~> route ~> check {
        rejection shouldBe MissingAttributeRejection(key)
      }
    }
  }

  "The optionalAttribute directive" should {
    val key = AttributeKey[String]("test-key")
    lazy val route =
      optionalAttribute(key) {
        _ match {
          case Some(value) =>
            complete(s"The attribute value was [$value]")
          case None =>
            complete(s"The attribute value was not set")
        }
      }

    "extract the attribute if present" in {
      Get("abc") ~> addAttribute(key, "the-value") ~> route ~> check {
        responseAs[String] shouldEqual "The attribute value was [the-value]"
      }
    }

    "extract None if no attribute was present for the given key" in {
      Get("abc") ~> route ~> check {
        responseAs[String] shouldEqual "The attribute value was not set"
      }
    }
  }
}

