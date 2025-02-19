/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.testkit

import akka.actor.ActorRef
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.pattern.ask
import akka.testkit.TestProbe
import akka.util.Timeout
import org.specs2.mutable.Specification

import scala.concurrent.duration._

class Specs2RouteTestSpec extends Specification with Specs2RouteTest {

  "The ScalatestRouteTest should support" should {

    "the most simple and direct route test" in {
      Get() ~> complete(HttpResponse()) ~> { rr => rr.awaitResult; rr.response } shouldEqual HttpResponse()
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
      implicit def serviceRef: ActorRef = service.ref
      implicit val askTimeout: Timeout = 1.second

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

    "failing the test inside the route" in {

      val route = get {
        failure("BOOM")
        complete(HttpResponse())
      }

      {
        Get() ~> route
      } must throwA[org.specs2.execute.FailureException]
    }

    "failing an assertion inside the route" in {
      val route = get {
        throw new AssertionError("test")
      }

      {
        Get() ~> route
      } must throwA[java.lang.AssertionError]
    }

    "internal server error" in {

      val route = get {
        throw new RuntimeException("BOOM")
      }

      Get().~>(route).~>(check {
        status shouldEqual InternalServerError
      })
    }
  }
}
