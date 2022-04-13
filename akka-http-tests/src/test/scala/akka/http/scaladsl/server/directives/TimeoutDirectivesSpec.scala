/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.server.directives

import akka.http.scaladsl.testkit.RouteTestTimeout
import akka.http.scaladsl.model.{ HttpResponse, StatusCodes }
import akka.http.scaladsl.server.RoutingSpec
import akka.testkit._

import scala.concurrent.duration._
import scala.concurrent.{ Future, Promise }

class TimeoutDirectivesSpec extends RoutingSpec {

  implicit val routeTestTimeout: RouteTestTimeout = RouteTestTimeout(5.seconds.dilated)

  "Request Timeout" should {
    "be configurable in routing layer" in {

      val route = path("timeout") {
        withRequestTimeout(3.seconds.dilated) {
          val response: Future[String] = slowFuture() // very slow
          complete(response)
        }
      }

      Get("/timeout") ~!> route ~> check {
        status should ===(StatusCodes.ServiceUnavailable)
      }
    }
  }

  "allow mapping the response" in {
    val timeoutResponse = HttpResponse(
      StatusCodes.EnhanceYourCalm,
      entity = "Unable to serve response within time limit, please enhance your calm.")

    val route =
      path("timeout") {
        // needs to be long because of the race between wRT and wRTR
        withRequestTimeout(1.second.dilated) {
          withRequestTimeoutResponse(request => timeoutResponse) {
            val response: Future[String] = slowFuture() // very slow
            complete(response)
          }
        }
      } ~
        path("equivalent") {
          // updates timeout and handler at
          withRequestTimeout(1.second.dilated, request => timeoutResponse) {
            val response: Future[String] = slowFuture() // very slow
            complete(response)
          }
        }

    Get("/timeout") ~!> route ~> check {
      status should ===(StatusCodes.EnhanceYourCalm)
    }

    Get("/equivalent") ~!> route ~> check {
      status should ===(StatusCodes.EnhanceYourCalm)
    }
  }

  def slowFuture(): Future[String] = Promise[String]().future

}
