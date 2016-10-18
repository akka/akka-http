/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server.directives

import akka.http.scaladsl.model.{ ContentTypes, HttpEntity, HttpResponse, StatusCodes }
import akka.http.scaladsl.server.IntegrationRoutingSpec
import akka.stream.scaladsl.Source
import akka.util.ByteString

import scala.concurrent.duration._
import scala.concurrent.{ Future, Promise }

class TimeoutDirectivesSpec extends IntegrationRoutingSpec {

  "Request Timeout" should {
    "be configurable in routing layer" in {

      val route = path("timeout") {
        withRequestTimeout(3.seconds) {
          val response: Future[String] = slowFuture() // very slow
          complete(response)
        }
      }

      Get("/timeout") ~!> route ~!> { response ⇒
        import response._

        status should ===(StatusCodes.ServiceUnavailable)
      }
    }
    "trigger when request is properly consumed" in {

      val route = path("timeout") {
        extractRequest { req ⇒
          req.discardEntityBytes()
          withRequestTimeout(3.seconds) {
            val response: Future[String] = slowFuture() // very slow
            complete(response)
          }
        }
      }

      val bytes = HttpEntity(ContentTypes.`text/plain(UTF-8)`, Source.repeat("abcdef ").take(100).map(ByteString(_)))
      Post("/timeout", bytes) ~!> route ~!> { response ⇒
        import response._

        status should ===(StatusCodes.ServiceUnavailable)
      }
    }
    "trigger even when request is properly consumed" in pendingUntilFixed {

      val route = path("timeout") {
        extractRequest { req ⇒
          // Intentionally forget to consume entity: req.discardEntityBytes()
          withRequestTimeout(3.seconds) {
            val response: Future[String] = slowFuture() // very slow
            complete(response)
          }
        }
      }

      val bytes = HttpEntity(ContentTypes.`text/plain(UTF-8)`, Source.repeat("abcdef ").take(100).map(ByteString(_)))
      Post("/timeout", bytes) ~!> route ~!> { response ⇒
        import response._

        status should ===(StatusCodes.ServiceUnavailable)
      }
    }
  }

  "allow mapping the response" in {
    val timeoutResponse = HttpResponse(
      StatusCodes.EnhanceYourCalm,
      entity = "Unable to serve response within time limit, please enchance your calm.")

    val route =
      path("timeout") {
        // needs to be long because of the race between wRT and wRTR
        withRequestTimeout(1.second) {
          withRequestTimeoutResponse(request ⇒ timeoutResponse) {
            val response: Future[String] = slowFuture() // very slow
            complete(response)
          }
        }
      } ~
        path("equivalent") {
          // updates timeout and handler at
          withRequestTimeout(1.second, request ⇒ timeoutResponse) {
            val response: Future[String] = slowFuture() // very slow
            complete(response)
          }
        }

    Get("/timeout") ~!> route ~!> { response ⇒
      import response._
      status should ===(StatusCodes.EnhanceYourCalm)
    }

    Get("/equivalent") ~!> route ~!> { response ⇒
      import response._
      status should ===(StatusCodes.EnhanceYourCalm)
    }
  }

  def slowFuture(): Future[String] = Promise[String].future

}
