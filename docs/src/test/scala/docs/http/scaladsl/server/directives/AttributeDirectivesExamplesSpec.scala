/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.scaladsl.server.directives

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.server.{ InvalidOriginRejection, MissingHeaderRejection, Route, RoutingSpec }
import docs.CompileOnlySpec
import org.scalatest.Inside

class AttributeDirectivesExamplesSpec extends RoutingSpec with CompileOnlySpec {
  "attribute" in {
    //#attribute
    val userId = AttributeKey[String]("user-id")

    val route =
      attribute(userId) { userId =>
        complete(s"The user is $userId")
      }

    // tests:
    Get("/") ~> addAttribute(userId, "Joe42") ~> route ~> check {
      responseAs[String] shouldEqual "The user is Joe42"
    }

    Get("/") ~> Route.seal(route) ~> check {
      status shouldEqual InternalServerError
    }
    //#attribute
  }
  "optionalAttribute" in {
    //#optionalAttribute
    val userId = AttributeKey[String]("user-id")

    val route =
      optionalAttribute(userId) {
        case Some(userId) => complete(s"The user is $userId")
        case None         => complete(s"No user was provided")
      } ~ // can also be written as:
        optionalAttribute(userId) { userId =>
          complete {
            userId match {
              case Some(u) => s"The user is $u"
              case _       => "No user was provided"
            }
          }
        }

    // tests:
    Get("/") ~> addAttribute(userId, "Joe42") ~> route ~> check {
      responseAs[String] shouldEqual "The user is Joe42"
    }
    Get("/") ~> Route.seal(route) ~> check {
      responseAs[String] shouldEqual "No user was provided"
    }
    //#optionalAttribute
  }
}
