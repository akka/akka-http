/*
 * Copyright (C) 2017-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.server.directives

import akka.http.caching.scaladsl.{ CachingSettings, LfuCacheSettings }
import akka.http.impl.util._
import akka.http.scaladsl.model.HttpMethods.GET
import akka.http.scaladsl.model.headers.CacheDirectives._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.model.{ HttpResponse, Uri }
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{ ExceptionHandler, RequestContext, RouteResult }
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.testkit._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.Future
import scala.concurrent.duration._

class CachingDirectivesSpec extends AnyWordSpec with Matchers with ScalatestRouteTest with CachingDirectives {

  val simpleKeyer: PartialFunction[RequestContext, Uri] = {
    case r: RequestContext if r.request.method == GET => r.request.uri
  }

  val countingService = {
    var i = 0
    cache(routeCache, simpleKeyer) {
      complete {
        i += 1
        i.toString
      }
    }
  }
  val errorService = {
    var i = 0
    cache(routeCache, simpleKeyer) {
      complete {
        i += 1
        HttpResponse(500 + i)
      }
    }
  }

  "the cache directive" should {
    "return and cache the response of the first GET" in {
      Get() ~> countingService ~> check { responseAs[String] shouldEqual "1" }
    }
    "return the cached response for a second GET" in {
      Get() ~> countingService ~> check { responseAs[String] shouldEqual "1" }
    }
    "return the cached response also for HttpFailures on GETs" in {
      Get() ~> errorService ~> check { response shouldEqual HttpResponse(501) }
    }
    "not cache responses for PUTs" in {
      Put() ~> countingService ~> check { responseAs[String] shouldEqual "2" }
    }
    "not cache responses for GETs if the request contains a `Cache-Control: no-cache` header" in {
      Get() ~> addHeader(`Cache-Control`(`no-cache`)) ~> countingService ~> check { responseAs[String] shouldEqual "3" }
    }
    "not cache responses for GETs if the request contains a `Cache-Control: max-age=0` header" in {
      Get() ~> addHeader(`Cache-Control`(`max-age`(0))) ~> countingService ~> check { responseAs[String] shouldEqual "4" }
    }

    "be transparent to exceptions thrown from its inner route" in {
      case object MyException extends SingletonException
      val myExceptionHandler = ExceptionHandler {
        case MyException => complete("Good")
      }

      Get() ~> handleExceptions(myExceptionHandler)(cache(routeCache, simpleKeyer) {
        _ => throw MyException // thrown directly
      }) ~> check { responseAs[String] shouldEqual "Good" }

      Get() ~> handleExceptions(myExceptionHandler)(cache(routeCache, simpleKeyer) {
        _.fail(MyException) // bubbling up
      }) ~> check { responseAs[String] shouldEqual "Good" }
    }
    "don't block cache when directive processing is slow" in {
      // Below we configure enough threads to run this blocking work load in parallel, so 100 times Thread.sleep(100) should still execute quickly
      // (creating threads will still take some time).
      // However, there was a bug where the directive is directly executed inside of `ConcurrentHashMap.computeIfAbsent`. When you block inside
      // of `computeIfAbsent` you will block hash map nodes and subsequent access to this node may run into the lock.
      // When done correctly, we immediate return a Future which is entered into the cache quickly and avoid this kind of locking.

      // small caches will have fewer nodes and will lock up with less concurrency
      val settings = CachingSettings(system).withLfuCacheSettings(LfuCacheSettings(system).withInitialCapacity(2))

      val route = cache(routeCache(settings), simpleKeyer) {
        get {
          path(IntNumber) { i =>
            // do some heavy work *before* returning a route
            Thread.sleep(100)
            complete("")
          }
        }
      }

      implicit val executor = system.dispatcher
      val routeFunc = RouteResult.routeToFunction(route)

      Future.traverse(1 to 1000) { i =>
        routeFunc(Get(s"/$i"))
      }.awaitResult(10.second.dilated)
    }
  }

  override def testConfigSource: String =
    """akka.actor.default-dispatcher.fork-join-executor {
      |  parallelism-min = 1000
      |  parallelism-max = 1000
      |}
      |""".stripMargin
}
