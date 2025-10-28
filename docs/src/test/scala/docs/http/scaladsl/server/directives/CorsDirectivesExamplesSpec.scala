/*
 * Copyright (C) 2009-2025 Lightbend Inc. <https://akka.io>
 */

package docs.http.scaladsl.server.directives

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{ HttpOrigin, Origin, `Access-Control-Allow-Credentials`, `Access-Control-Allow-Methods`, `Access-Control-Allow-Origin`, `Access-Control-Max-Age`, `Access-Control-Request-Method` }
import akka.http.scaladsl.server.{ Route, RoutingSpec }
import docs.CompileOnlySpec

class CorsDirectivesExamplesSpec extends RoutingSpec with CompileOnlySpec {
  "cors" in {
    //#cors
    val route =
      cors() {
        complete(Ok)
      }

    // tests:

    // preflight
    Options() ~> Origin(HttpOrigin("http://example.com")) ~> `Access-Control-Request-Method`(HttpMethods.GET) ~> route ~> check {
      status shouldBe StatusCodes.OK
      response.headers should contain theSameElementsAs Seq(
        `Access-Control-Allow-Origin`(HttpOrigin("http://example.com")),
        `Access-Control-Allow-Methods`(HttpMethods.GET, HttpMethods.POST, HttpMethods.HEAD, HttpMethods.OPTIONS),
        `Access-Control-Max-Age`(1800),
        `Access-Control-Allow-Credentials`(allow = true)
      )
    }

    // regular request
    Get() ~> Origin(HttpOrigin("http://example.com")) ~> route ~> check {
      status shouldEqual OK
      response.headers should contain theSameElementsAs Seq(
        `Access-Control-Allow-Origin`(HttpOrigin("http://example.com")),
        `Access-Control-Allow-Credentials`(allow = true)
      )
    }

    //#cors
  }
}
