/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.server.directives

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.{Directive, ValidationRejection}
import akka.http.scaladsl.server.directives.DirectiveMonad._
import akka.http.scaladsl.server.{Route, RoutingSpec}

class DirectiveMonadSpec extends RoutingSpec {

  "extract method and 1 path segment and return string" in {
    val route: Route = for {
      _ ← get
      segment ← path("some" / Segment)
      method ← extractMethod
    } yield s"method: ${method.value}, segment: $segment"
    Get("/some/1") ~> route ~> check {
      responseAs[String] shouldEqual "method: GET, segment: 1"
    }
  }

  "extract 2 path segments and return string" in {
    val route: Route = for {
      _ ← get
      _ ← pathPrefix("test")
      (segment1, segment2) ← path("some" / Segment / Segment)
    } yield {
      s"segment1: $segment1, segment2: $segment2"
    }
    Get("/test/some/1/2") ~> route ~> check {
      responseAs[String] shouldEqual "segment1: 1, segment2: 2"
    }
  }

  "extract 3 path segments and return string" in {
    val route: Route = for {
      _ ← get
      (segment1, segment2, segment3) ← path("test" / "some" / Segment / Segment / Segment)
      method ← extractMethod
    } yield s"method: ${method.value}, segment1: $segment1, segment2: $segment2, segment3: $segment3"
    Get("/test/some/1/2/3") ~> route ~> check {
      responseAs[String] shouldEqual "method: GET, segment1: 1, segment2: 2, segment3: 3"
    }
  }

  "factor out common directives from routes" in {
    val commonDirective = for {
      _ ← get
      _ ← pathPrefix("test")
    } yield ()

    val route1: Route = for {
      _ ← path("r1")
    } yield "OK1" // A `complete()` will be added here automatically.

    val route2: Route = for {
      _ ← path("r2")
    } yield complete("OK2") // We can write `yield complete()` as well.

    val route: Route = for {
      _ ← commonDirective
    } yield route1 ~ route2

    Get("/test/r1") ~> route ~> check {
      responseAs[String] shouldEqual "OK1"
      status shouldEqual StatusCodes.OK
    }

    Get("/test/r2") ~> route ~> check {
      responseAs[String] shouldEqual "OK2"
      status shouldEqual StatusCodes.OK
    }
  }

  "use recover() and other methods on wrapped directives" in {
    def commonDirective(x: Int) = for {
      _ ← get
      _ ← pathPrefix("test")
      if x > 0
    } yield ()

    def route(x: Int): Route = for {
      _ ← commonDirective(x) & path("t1")
      header ← headerValueByName("nonexistent").recover(_ ⇒ provide("header"))
    } yield s"OK: $header"

    Get("/test/t1") ~> route(1) ~> check {
      responseAs[String] shouldEqual "OK: header"
      status shouldEqual StatusCodes.OK
    }

    Get("/test/t1") ~> route(0) ~> check {
      rejections shouldEqual List()
    }
  }
}
