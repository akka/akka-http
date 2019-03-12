/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.server

import akka.http.impl.util.WithLogCapturing
import akka.http.scaladsl.model.ContentTypes.`text/plain(UTF-8)`
import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.model.StatusCodes.InternalServerError
import akka.stream.scaladsl.Source
import akka.util.ByteString
import org.scalatest.concurrent.Eventually._
import org.scalatest.concurrent.ScalaFutures
import akka.http.ccompat.imm._

import scala.concurrent.Future

class DiscardEntityDefaultExceptionHandlerSpec extends RoutingSpec with ScalaFutures with WithLogCapturing {

  private val route = concat(
    path("crash") {
      throw new RuntimeException("BOOM!")
    }, path("crashAfterConsuming") {
      extractRequestEntity { entity ⇒
        val future: Future[String] = entity.dataBytes.runFold(ByteString.empty)(_ ++ _).map(_ ⇒ throw new RuntimeException("KABOOM!"))
        complete(future)
      }
    }
  )

  trait Fixture {
    @volatile
    var streamConsumed = false
    val thousandElements: Stream[ByteString] = Stream.continually(ByteString("foo")).take(999).lazyAppendedAll {
      streamConsumed = true
      Seq(ByteString("end"))
    }

  }

  "Default ExceptionHandler" should {
    "rejectEntity by default" in new Fixture {
      streamConsumed shouldBe false
      Get("/crash", HttpEntity(`text/plain(UTF-8)`, Source[ByteString](thousandElements))) ~> Route.seal(route) ~> check {
        status shouldBe InternalServerError
        eventually { // Stream will be eventually consumed, once all the stream bytes are successfully discarded
          streamConsumed shouldBe true
        }
      }
    }

    "rejectEntity by default even if consumed already" in new Fixture {
      streamConsumed shouldBe false
      Get("/crashAfterConsuming", HttpEntity(`text/plain(UTF-8)`, Source[ByteString](thousandElements))) ~> Route.seal(route) ~> check {
        // Stream should be consumed immediately after the request finishes
        streamConsumed shouldBe true
        status shouldBe InternalServerError
      }
    }
  }

}
