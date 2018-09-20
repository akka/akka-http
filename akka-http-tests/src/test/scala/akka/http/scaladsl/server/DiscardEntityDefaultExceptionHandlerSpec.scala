/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.server

import akka.http.scaladsl.model.ContentTypes.`text/plain(UTF-8)`
import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.model.StatusCodes.InternalServerError
import akka.stream.scaladsl.Source
import akka.util.ByteString
import org.scalatest.concurrent.Eventually._
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.Future

class DiscardEntityDefaultExceptionHandlerSpec extends RoutingSpec with ScalaFutures {

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

  private val numElems = 1000
  @volatile
  private var elementsEmitted = 0
  private def gimmeElement(): ByteString = {
    elementsEmitted = elementsEmitted + 1
    ByteString("Foo")
  }

  private val ThousandElements: Stream[ByteString] = Stream.continually(gimmeElement()).take(numElems)
  private val RequestToCrash = Get("/crash", HttpEntity(`text/plain(UTF-8)`, Source[ByteString](ThousandElements)))
  private val RequestToCrashConsumingFirst = Get("/crashAfterConsuming", HttpEntity(`text/plain(UTF-8)`, Source[ByteString](ThousandElements)))

  "Default ExceptionHandler" should {
    "rejectEntity by default" in {
      RequestToCrash ~> Route.seal(route) ~> check {
        status shouldBe InternalServerError
        eventually {
          elementsEmitted shouldBe numElems
        }
      }
    }
    "rejectEntity by default even if consumed already" in {
      RequestToCrashConsumingFirst ~> Route.seal(route) ~> check {
        status shouldBe InternalServerError
        eventually {
          elementsEmitted shouldBe numElems
        }
      }
    }
  }

}
