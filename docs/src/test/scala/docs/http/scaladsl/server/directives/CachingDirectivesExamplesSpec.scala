package docs.http.scaladsl.server.directives

import docs.http.scaladsl.server.RoutingSpec
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.model.headers.CacheDirectives._
import akka.http.scaladsl.server.directives.CachingDirectives

class CachingDirectivesExamplesSpec extends RoutingSpec with CachingDirectives {
  "cache" in {
    //#cache
    var i = 0
    val route =
      cache(routeCache()) {
        complete {
          i += 1
          i.toString
        }
      }

    Get("/") ~> route ~> check {
      responseAs[String] shouldEqual "1"
    }
    // now cached
    Get("/") ~> route ~> check {
      responseAs[String] shouldEqual "1"
    }
    // caching prevented
    Get("/") ~> `Cache-Control`(`no-cache`) ~> route ~> check {
      responseAs[String] shouldEqual "2"
    }
    //#cache
  }
  "always-cache" in {
    //#always-cache
    var i = 0
    val route =
      alwaysCache(routeCache()) {
        complete {
          i += 1
          i.toString
        }
      }

    Get("/") ~> route ~> check {
      responseAs[String] shouldEqual "1"
    }
    // now cached
    Get("/") ~> route ~> check {
      responseAs[String] shouldEqual "1"
    }
    Get("/") ~> `Cache-Control`(`no-cache`) ~> route ~> check {
      responseAs[String] shouldEqual "1"
    }
    //#always-cache
  }
  "cachingProhibited" in {
    //#caching-prohibited
    val route =
      cachingProhibited {
        complete("abc")
      }

    Get("/") ~> route ~> check {
      handled shouldEqual false
    }
    Get("/") ~> `Cache-Control`(`no-cache`) ~> route ~> check {
      responseAs[String] shouldEqual "abc"
    }
    //#caching-prohibited
  }
}