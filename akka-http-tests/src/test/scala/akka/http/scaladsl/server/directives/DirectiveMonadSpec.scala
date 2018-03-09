package akka.http.scaladsl.server.directives

import akka.http.scaladsl.server.directives.DirectiveMonad._
import akka.http.scaladsl.server.{ Route, RoutingSpec }

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

}
