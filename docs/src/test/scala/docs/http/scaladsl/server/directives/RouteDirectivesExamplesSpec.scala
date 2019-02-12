/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.scaladsl.server.directives

import akka.http.scaladsl.server.RoutingSpec
import docs.CompileOnlySpec

import scala.concurrent.Future

//#complete-examples
//#reject-examples
import akka.http.scaladsl.model._
//#reject-examples
//#complete-examples

//#complete-examples
import akka.http.scaladsl.model.ContentTypes._
import akka.http.scaladsl.model.headers.`Content-Type`

//#complete-examples

//#reject-examples
import akka.http.scaladsl.server.ValidationRejection

//#reject-examples

import akka.http.scaladsl.server.Route
import akka.testkit.EventFilter

class RouteDirectivesExamplesSpec extends RoutingSpec with CompileOnlySpec {

  "complete-examples" in {
    //#complete-examples
    val route =
      path("a") {
        complete(HttpResponse(entity = "foo"))
      } ~
        path("b") {
          complete(StatusCodes.OK)
        } ~
        path("c") {
          complete(StatusCodes.Created -> "bar")
        } ~
        path("d") {
          complete(201 -> "bar")
        } ~
        path("e") {
          complete(StatusCodes.Created, List(`Content-Type`(`text/plain(UTF-8)`)), "bar")
        } ~
        path("f") {
          complete(201, List(`Content-Type`(`text/plain(UTF-8)`)), "bar")
        } ~
        path("g") {
          complete(Future { StatusCodes.Created -> "bar" })
        } ~
        (path("h") & complete("baz")) // `&` also works with `complete` as the 2nd argument

    // tests:
    Get("/a") ~> route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[String] shouldEqual "foo"
    }

    Get("/b") ~> route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[String] shouldEqual "OK"
    }

    Get("/c") ~> route ~> check {
      status shouldEqual StatusCodes.Created
      responseAs[String] shouldEqual "bar"
    }

    Get("/d") ~> route ~> check {
      status shouldEqual StatusCodes.Created
      responseAs[String] shouldEqual "bar"
    }

    Get("/e") ~> route ~> check {
      status shouldEqual StatusCodes.Created
      header[`Content-Type`] shouldEqual Some(`Content-Type`(`text/plain(UTF-8)`))
      responseAs[String] shouldEqual "bar"
    }

    Get("/f") ~> route ~> check {
      status shouldEqual StatusCodes.Created
      header[`Content-Type`] shouldEqual Some(`Content-Type`(`text/plain(UTF-8)`))
      responseAs[String] shouldEqual "bar"
    }

    Get("/g") ~> route ~> check {
      status shouldEqual StatusCodes.Created
      responseAs[String] shouldEqual "bar"
    }

    Get("/h") ~> route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[String] shouldEqual "baz"
    }
    //#complete-examples
  }

  "reject-examples" in {
    //#reject-examples
    val route =
      path("a") {
        reject // don't handle here, continue on
      } ~
        path("a") {
          complete("foo")
        } ~
        path("b") {
          // trigger a ValidationRejection explicitly
          // rather than through the `validate` directive
          reject(ValidationRejection("Restricted!"))
        }

    // tests:
    Get("/a") ~> route ~> check {
      responseAs[String] shouldEqual "foo"
    }

    Get("/b") ~> route ~> check {
      rejection shouldEqual ValidationRejection("Restricted!")
    }
    //#reject-examples
  }

  "redirect-examples" in {
    //#redirect-examples
    val route =
      pathPrefix("foo") {
        pathSingleSlash {
          complete("yes")
        } ~
          pathEnd {
            redirect("/foo/", StatusCodes.PermanentRedirect)
          }
      }

    // tests:
    Get("/foo/") ~> route ~> check {
      responseAs[String] shouldEqual "yes"
    }

    Get("/foo") ~> route ~> check {
      status shouldEqual StatusCodes.PermanentRedirect
      responseAs[String] shouldEqual """The request, and all future requests should be repeated using <a href="/foo/">this URI</a>."""
    }
    //#redirect-examples
  }

  "failwith-examples" in EventFilter[RuntimeException](
    start = "Error during processing of request: 'Oops.'. Completing with 500 Internal Server Error response.",
    occurrences = 1
  ).intercept {
    //#failwith-examples
    val route =
      path("foo") {
        failWith(new RuntimeException("Oops."))
      }

    // tests:
    Get("/foo") ~> Route.seal(route) ~> check {
      status shouldEqual StatusCodes.InternalServerError
      responseAs[String] shouldEqual "There was an internal server error."
    }
    //#failwith-examples
  }

}
