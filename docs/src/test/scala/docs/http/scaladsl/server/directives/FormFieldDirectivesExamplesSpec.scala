/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.scaladsl.server.directives

import akka.http.scaladsl.server.Route
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.RoutingSpec
import docs.CompileOnlySpec

class FormFieldDirectivesExamplesSpec extends RoutingSpec with CompileOnlySpec {
  "formFields" in {
    //#formFields
    val route =
      formFields("color", "age".as[Int], "direction" ! "up") { (color, age, _) =>
        complete(s"The color is '$color' and the age ten years ago was ${age - 10}")
      }

    // tests:
    Post("/", FormData("direction" -> "up", "color" -> "blue", "age" -> "68")) ~> route ~> check {
      responseAs[String] shouldEqual "The color is 'blue' and the age ten years ago was 58"
    }

    Get("/") ~> Route.seal(route) ~> check {
      status shouldEqual StatusCodes.BadRequest
      responseAs[String] shouldEqual "Request is missing required form field 'color'"
    }
    //#formFields
  }
  "formField" in {
    //#formField
    val route =
      concat(
        formField("color") { color =>
          complete(s"The color is '$color'")
        },
        formField("id".as[Int]) { id =>
          complete(s"The id is '$id'")
        }
      )

    // tests:
    Post("/", FormData("color" -> "blue")) ~> route ~> check {
      responseAs[String] shouldEqual "The color is 'blue'"
    }

    Get("/") ~> Route.seal(route) ~> check {
      status shouldEqual StatusCodes.BadRequest
      responseAs[String] shouldEqual "Request is missing required form field 'color'"
    }
    //#formField
  }
  "formFieldMap" in {
    //#formFieldMap
    val route =
      formFieldMap { fields =>
        def formFieldString(formField: (String, String)): String =
          s"""${formField._1} = '${formField._2}'"""
        complete(s"The form fields are ${fields.map(formFieldString).mkString(", ")}")
      }

    // tests:
    Post("/", FormData("color" -> "blue", "count" -> "42")) ~> route ~> check {
      responseAs[String] shouldEqual "The form fields are color = 'blue', count = '42'"
    }
    Post("/", FormData("x" -> "1", "x" -> "5")) ~> route ~> check {
      responseAs[String] shouldEqual "The form fields are x = '5'"
    }
    //#formFieldMap
  }
  "formFieldMultiMap" in {
    //#formFieldMultiMap
    val route =
      formFieldMultiMap { fields =>
        complete("There are " +
          s"form fields ${fields.map(x => x._1 + " -> " + x._2.size).mkString(", ")}")
      }

    // tests:
    Post("/", FormData("color" -> "blue", "count" -> "42")) ~> route ~> check {
      responseAs[String] shouldEqual "There are form fields color -> 1, count -> 1"
    }
    Post("/", FormData("x" -> "23", "x" -> "4", "x" -> "89")) ~> route ~> check {
      responseAs[String] shouldEqual "There are form fields x -> 3"
    }
    //#formFieldMultiMap
  }
  "formFieldSeq" in {
    //#formFieldSeq
    val route =
      formFieldSeq { fields =>
        def formFieldString(formField: (String, String)): String =
          s"""${formField._1} = '${formField._2}'"""
        complete(s"The form fields are ${fields.map(formFieldString).mkString(", ")}")
      }

    // tests:
    Post("/", FormData("color" -> "blue", "count" -> "42")) ~> route ~> check {
      responseAs[String] shouldEqual "The form fields are color = 'blue', count = '42'"
    }
    Post("/", FormData("x" -> "23", "x" -> "4", "x" -> "89")) ~> route ~> check {
      responseAs[String] shouldEqual "The form fields are x = '23', x = '4', x = '89'"
    }
    //#formFieldSeq
  }

}
