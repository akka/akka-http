/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.server

import akka.http.scaladsl.model.ContentTypes.`text/plain(UTF-8)`
import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.model.StatusCodes.NotFound
import akka.stream.scaladsl.Source
import akka.util.ByteString
import org.scalatest.concurrent.Eventually._
import org.scalatest.concurrent.ScalaFutures

class DiscardEntityDefaultRejectionHandlerSpec extends RoutingSpec with ScalaFutures {

  private val route = path("foo") {
    complete("bar")
  }

  private val numElems = 1000
  @volatile
  private var elementsEmitted = 0
  private def gimmeElement(): ByteString = {
    elementsEmitted = elementsEmitted + 1
    ByteString("Foo")
  }

  private val ThousandElements: Stream[ByteString] = Stream.continually(gimmeElement()).take(numElems)
  private val RequestToNotHandled = Get("/bar", HttpEntity(`text/plain(UTF-8)`, Source[ByteString](ThousandElements)))

  "Default RejectionHandler" should {
    "rejectEntity by default" in {
      RequestToNotHandled ~> Route.seal(route) ~> check {
        status shouldBe NotFound
        eventually {
          elementsEmitted shouldBe numElems
        }
      }
    }
  }

}
