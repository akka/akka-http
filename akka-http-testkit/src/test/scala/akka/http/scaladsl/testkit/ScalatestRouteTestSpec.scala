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

import scala.concurrent.Await
import scala.concurrent.Future

class ScalatestRouteTestSpec extends FreeSpec with Matchers with ScalatestRouteTest {

  "The ScalatestRouteTest should support" - {

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

    "running on akka dispatcher threads" in Await.result(Future {
      // https://github.com/akka/akka-http/pull/2526
      // Check will block while waiting on the response, this might lead to starvation
      // on the BatchingExecutor of akka's dispatcher if the blocking is not managed properly.
      Get() ~> complete(Future(HttpResponse())) ~> check {
        status shouldEqual OK
      }
    }, 5.seconds)

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
