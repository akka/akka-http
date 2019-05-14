/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.testkit

import scala.concurrent.duration._
import org.scalatest.FreeSpec
import org.scalatest.Matchers
import akka.testkit._
import akka.util.Timeout
import akka.pattern.ask
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.server._
import akka.http.scaladsl.model._
import StatusCodes._
import HttpMethods._
import Directives._
import scala.concurrent.Future
import scala.concurrent.Await
import scala.concurrent.Promise
import akka.http.scaladsl.unmarshalling.Unmarshal
import scala.concurrent.ExecutionContext
import akka.actor.ActorSystem

class ScalatestRouteTestSpec extends FreeSpec with Matchers with ScalatestRouteTest {
  implicit val timeout = RouteTestTimeout(5.seconds)

  "The ScalatestRouteTest should support" - {

    "the most simple and direct route test" in {
      Get() ~> complete(HttpResponse()) ~> { rr ⇒ rr.awaitResult; rr.response } shouldEqual HttpResponse()
    }

    "a test using a directive and some checks" in {
      val pinkHeader = RawHeader("Fancy", "pink")
      Get() ~> addHeader(pinkHeader) ~> {
        respondWithHeader(pinkHeader) {
          complete("abc")
        }
      } ~> check {
        status shouldEqual OK
        responseEntity shouldEqual HttpEntity(ContentTypes.`text/plain(UTF-8)`, "abc")
        header("Fancy") shouldEqual Some(pinkHeader)
      }
    }

    //tests that run the route without check() - these complete immediately even if the future doesn't
    "a route that immediately responds in Future.successful - without check {}" in {
      stringResponse(Get("http://localhost/futures/quick") ~> Route.seal(Routes.route)) shouldEqual "done-quick"
    }

    "a route with a quick async boundary - without check {}" in {
      stringResponse(Get("http://localhost/futures/buggy") ~> Route.seal(Routes.route)) shouldEqual "done-buggy"
    }

    "a long running future - without check {}" in {
      stringResponse(Get("http://localhost/futures/long") ~> Route.seal(Routes.route)) shouldEqual "done-long"
    }

    def stringResponse(result: RouteTestResult): String = {
      Await.result(Unmarshal(result.response).to[String], 5.seconds)
    }

    //tests that run after an async boundary - these complete after the future is completed or the timeout hits
    "a route that immediately responds in Future.successful - with check {}" in afterAsyncBoundary {
      Get("http://localhost/futures/quick") ~> Route.seal(Routes.route) ~> check {
        responseAs[String] shouldEqual "done-quick"
      }
    }

    "a route with a quick async boundary - with check {}" in afterAsyncBoundary {
      Get("http://localhost/futures/buggy") ~> Route.seal(Routes.route) ~> check {
        responseAs[String] shouldEqual "done-buggy"
      }
    }

    "a long running future - with check {}" in afterAsyncBoundary {
      Get("http://localhost/futures/long") ~> Route.seal(Routes.route) ~> check {
        responseAs[String] shouldEqual "done-long"
      }
    }

    def afterAsyncBoundary[A](a: => A): A = {
      Await.result(Future.successful(()).map(_ => a), 10.seconds)
    }


    "proper rejection collection" in {
      Post("/abc", "content") ~> {
        (get | put) {
          complete("naah")
        }
      } ~> check {
        rejections shouldEqual List(MethodRejection(GET), MethodRejection(PUT))
      }
    }

    "separation of route execution from checking" in {
      val pinkHeader = RawHeader("Fancy", "pink")

      case object Command
      val service = TestProbe()
      val handler = TestProbe()
      implicit def serviceRef = service.ref
      implicit val askTimeout: Timeout = 1.second.dilated

      val result =
        Get() ~> pinkHeader ~> {
          respondWithHeader(pinkHeader) {
            complete(handler.ref.ask(Command).mapTo[String])
          }
        } ~> runRoute

      handler.expectMsg(Command)
      handler.reply("abc")

      check {
        status shouldEqual OK
        responseEntity shouldEqual HttpEntity(ContentTypes.`text/plain(UTF-8)`, "abc")
        header("Fancy") shouldEqual Some(pinkHeader)
      }(result)
    }
  }
}

object Routes {
  def route(implicit ec: ExecutionContext, system: ActorSystem): Route =
    pathPrefix("futures") {
      pathPrefix("quick") {
        complete(Future.successful("done-quick"))
      } ~ pathPrefix("buggy") {
        complete(Future.successful(()).map(_ ⇒ "done-buggy"))
      } ~ pathPrefix("long") {
        complete(longFuture("done-long"))
      }
    }

  private def longFuture(result: String)(implicit ec: ExecutionContext, system: ActorSystem): Future[String] = {
    val promise = Promise[String]()

    system.scheduler.scheduleOnce(1.seconds) {
      val _ = promise.success(result)
    }

    promise.future
  }
}
