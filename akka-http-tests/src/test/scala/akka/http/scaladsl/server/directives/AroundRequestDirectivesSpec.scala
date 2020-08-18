/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.server
package directives

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ HttpResponse, StatusCodes, _ }
import akka.http.scaladsl.testkit.RouteTestTimeout
import akka.stream.scaladsl.Source
import akka.testkit.SocketUtil
import akka.util.ByteString
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.{ Eventually, ScalaFutures }
import org.scalatest.time.{ Millis, Span }

import scala.concurrent.{ Future, Promise }
import scala.concurrent.duration._

class AroundRequestDirectivesSpec extends RoutingSpec with BeforeAndAfterEach with Eventually with ScalaFutures {

  override def testConfigSource =
    """
    akka.loggers = ["akka.testkit.TestEventListener"]
  """

  override def beforeEach(): Unit = {
    results = Map.empty[String, HttpResponse]
    super.beforeEach()
  }
  override implicit def patienceConfig: PatienceConfig = PatienceConfig(scaled(Span(10000, Millis)), scaled(Span(15, Millis)))

  implicit def default(implicit system: ActorSystem) = RouteTestTimeout(new DurationInt(10).seconds)

  val behaviour: RequestContext => HttpResponse => HttpResponse = ctx => {
    // Request start function
    val testKey = ctx.request.uri.toRelative.toString

    // Response OnComplete Function
    (response: HttpResponse) => results += (testKey -> response); response
  }

  var results = Map.empty[String, HttpResponse]

  // Run route method for testing Request-Timeout scenarios
  def runRoute(route: Route, routePath: String): HttpResponse = {
    val (hostname, port) = SocketUtil.temporaryServerHostnameAndPort()
    val binding = Http().bindAndHandle(route, hostname, port)
    val response = Http().singleRequest(HttpRequest(uri = s"http://$hostname:$port/$routePath")).futureValue
    binding.flatMap(_.unbind()).futureValue
    response.discardEntityBytes()
    response
  }

  "the aroundRequest directive wrapping an unsealed route" should {
    "invoke test function on success" in {
      val route: Route =
        aroundRequest(behaviour) {
          entity(as[String]) { _ =>
            completeOk
          }
        }
      runRoute(route, "test")

      val actual = results("/test")
      actual.status should ===(StatusCodes.OK)
    }
    "invoke test function on reject" in {
      val route: Route =
        aroundRequest(behaviour) {
          entity(as[String]) { _ =>
            reject(MethodRejection(HttpMethods.GET))
          }
        }
      runRoute(route, "test")

      val actual = results("/test")
      actual.status should ===(StatusCodes.MethodNotAllowed)
    }
    "invoke test function on failure" in {
      val route: Route =
        aroundRequest(behaviour) {
          entity(as[String]) { _ =>
            fail(new Exception())
          }
        }
      runRoute(route, "test")

      val actual = results("/test")
      actual.status should ===(StatusCodes.InternalServerError)
    }
    "invoke test function on an exception" in {
      val route: Route =
        aroundRequest(behaviour) {
          entity(as[String]) { _ =>
            throw new Exception()
          }
        }
      runRoute(route, "test")

      val actual = results("/test")
      actual.status should ===(StatusCodes.InternalServerError)
    }
    "handle timeouts" in {
      def slowFuture(): Future[String] = Promise[String].future
      val route: Route =
        aroundRequest(behaviour) {
          withRequestTimeout(1.seconds) { // modifies the global akka.http.server.request-timeout for this request
            val response: Future[String] = slowFuture() // very slow
            complete(response)
          }
        }

      runRoute(route, "test")

      val actual = results("/test")
      actual.status should ===(StatusCodes.ServiceUnavailable)
    }
  }

  "the aroundRequest directive when used on a chunked response" should {
    "call the onDone function when last bytes are sent" in {

      var duration = 0L

      val timed: RequestContext => HttpResponse => HttpResponse = ctx => {
        val start = System.nanoTime()

        (response: HttpResponse) => duration = System.nanoTime() - start; response
      }

      val route: Route =
        aroundRequest(timed) {
          entity(as[String]) { _ =>
            val s = Source.tick(0.seconds, 1.second, "x").take(6).map(ByteString(_))
            complete(HttpEntity(ContentTypes.`text/plain(UTF-8)`, s))
          }
        }
      runRoute(route, "test").status should ===(StatusCodes.OK)

      eventually {
        duration >= 5000000000L shouldBe true
      }
    }
  }

  "the aroundRequest directive when used multiple times" should {
    "call multiple onDone functions on success" in {

      var outerFunctionCounter = 0
      var innerFunctionCounter = 0

      val outer: RequestContext => HttpResponse => HttpResponse = ctx => {
        outerFunctionCounter += 1
        (response: HttpResponse) => outerFunctionCounter += 1; response
      }
      val inner: RequestContext => HttpResponse => HttpResponse = ctx => {
        innerFunctionCounter += 1
        (response: HttpResponse) => innerFunctionCounter += 1; response
      }

      val route: Route =
        aroundRequest(outer) {
          aroundRequest(inner) {
            entity(as[String]) { _ =>
              completeOk
            }
          }
        }
      runRoute(route, "test").status should ===(StatusCodes.OK)
      eventually {
        outerFunctionCounter shouldBe 2
        innerFunctionCounter shouldBe 2
      }
    }
  }
}
