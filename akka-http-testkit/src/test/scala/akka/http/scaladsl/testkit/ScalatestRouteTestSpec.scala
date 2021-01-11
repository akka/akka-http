/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.testkit

import scala.concurrent.duration._
import akka.testkit._
import akka.util.{ ByteString, Timeout }
import akka.pattern.ask
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.server._
import akka.http.scaladsl.model._
import StatusCodes._
import HttpMethods._
import Directives._
import akka.stream.scaladsl.Source
import org.scalatest.exceptions.TestFailedException
import headers.`X-Forwarded-Proto`
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.Await
import scala.concurrent.Future
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class ScalatestRouteTestSpec extends AnyFreeSpec with Matchers with ScalatestRouteTest with ScalaFutures {
  override def testConfigSource: String = "akka.http.server.transparent-head-requests = on" // see test below

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

    "a test using ~!> and some checks" in {
      // raw here, should have been parsed into modelled header when going through an actual server when using `~!>`
      val extraHeader = RawHeader("X-Forwarded-Proto", "abc")
      Get() ~!> {
        respondWithHeader(extraHeader) {
          complete("abc")
        }
      } ~> check {
        status shouldEqual OK
        responseEntity shouldEqual HttpEntity(ContentTypes.`text/plain(UTF-8)`, "abc")
        header[`X-Forwarded-Proto`].get shouldEqual `X-Forwarded-Proto`("abc")
      }
    }

    "a test checking a route that returns infinite chunks" in {
      Get() ~> {
        val infiniteSource =
          Source.unfold(0L)((acc) => Some((acc + 1, acc)))
            .throttle(1, 20.millis)
            .map(i => ByteString(i.toString))
        complete(HttpEntity(ContentTypes.`application/octet-stream`, infiniteSource))
      } ~> check {
        status shouldEqual OK
        contentType shouldEqual ContentTypes.`application/octet-stream`
        val future = chunksStream.take(5).runFold(Vector.empty[Int])(_ :+ _.data.utf8String.toInt)
        future.futureValue shouldEqual (0 until 5).toVector

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

    "failing the test inside the route" in {

      val route = get {
        fail()
      }

      assertThrows[TestFailedException] {
        Get() ~> route
      }
    }

    "throwing an AssertionError inside the route" in {
      val route = get {
        throw new AssertionError("test")
      }

      assertThrows[AssertionError] {
        Get() ~> route
      }
    }

    "internal server error" in {

      val route = get {
        throw new RuntimeException("BOOM")
      }

      Get() ~> route ~> check {
        status shouldEqual InternalServerError
      }
    }

    "fail if testing a HEAD request with ~> and `transparent-head-request = on`" in {
      def runTest(): Unit = Head() ~> complete("Ok") ~> check {}

      val ex = the[Exception] thrownBy (runTest())
      ex.getMessage shouldEqual
        "`akka.http.server.transparent-head-requests = on` not supported in RouteTest using `~>`. " +
        "Use `~!>` instead for a full-stack test, e.g. `req ~!> route ~> check {...}`"
    }
  }
}
