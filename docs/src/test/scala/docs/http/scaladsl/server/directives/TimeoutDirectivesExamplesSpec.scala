/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.scaladsl.server.directives

import akka.http.scaladsl.model.{ HttpResponse, StatusCodes }
import akka.http.scaladsl.server.{ Route, RoutingSpec }
import docs.CompileOnlySpec
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.model.HttpEntity._
import akka.http.scaladsl.model._
import akka.http.scaladsl.testkit.RouteTestTimeout
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future, Promise }
import akka.testkit.{ AkkaSpec, SocketUtil2 }

class TimeoutDirectivesExamplesSpec extends RoutingSpec
  with ScalaFutures with CompileOnlySpec {
  //#testSetup

  implicit val timeout: RouteTestTimeout = RouteTestTimeout(3.seconds)
  override def testConfigSource: String =
    "akka.http.server.request-timeout = infinite\n" +
      super.testConfigSource

  def slowFuture(): Future[String] = Future.never
  //#testSetup

  // demonstrates that timeout is correctly set despite infinite initial value of akka.http.server.request-timeout
  "Request Timeout" should {
    "be configurable in routing layer despite infinite initial value of request-timeout" in {
      //#withRequestTimeout-plain
      val route =
        path("timeout") {
          withRequestTimeout(1.seconds) { // modifies the global akka.http.server.request-timeout for this request
            val response: Future[String] = slowFuture() // very slow
            complete(response)
          }
        }

      // check
      Get("/timeout") ~!> route ~> check {
        status should ===(StatusCodes.ServiceUnavailable) // the timeout response
      }
      //#withRequestTimeout-plain
    }
    "without timeout" in compileOnlySpec {
      //#withoutRequestTimeout
      val route =
        path("timeout") {
          withoutRequestTimeout {
            val response: Future[String] = slowFuture() // very slow
            complete(response)
          }
        }

      // no check as there is no time-out, the future would time out failing the test
      //#withoutRequestTimeout
    }

    "allow mapping the response while setting the timeout" in {
      //#withRequestTimeout-with-handler
      val timeoutResponse = HttpResponse(
        StatusCodes.EnhanceYourCalm,
        entity = "Unable to serve response within time limit, please enhance your calm.")

      val route =
        path("timeout") {
          // updates timeout and handler at
          withRequestTimeout(1.milli, request => timeoutResponse) {
            val response: Future[String] = slowFuture() // very slow
            complete(response)
          }
        }

      // check
      Get("/timeout") ~!> route ~> check {
        status should ===(StatusCodes.EnhanceYourCalm) // the timeout response
      }
      //#withRequestTimeout-with-handler
    }

    // make it compile only to avoid flaking in slow builds
    "allow mapping the response" in compileOnlySpec {
      //#withRequestTimeoutResponse
      val timeoutResponse = HttpResponse(
        StatusCodes.EnhanceYourCalm,
        entity = "Unable to serve response within time limit, please enhance your calm.")

      val route =
        path("timeout") {
          withRequestTimeout(100.milli) { // racy! for a very short timeout like 1.milli you can still get 503
            withRequestTimeoutResponse(request => timeoutResponse) {
              val response: Future[String] = slowFuture() // very slow
              complete(response)
            }
          }
        }

      // check
      Get("/timeout") ~!> route ~> check {
        status should ===(StatusCodes.EnhanceYourCalm) // the timeout response
      }
      //#withRequestTimeoutResponse
    }

    // read currently set timeout
    "allow extraction of currently set timeout" in {
      //#extractRequestTimeout
      val timeout1 = 500.millis
      val timeout2 = 1000.millis
      val route =
        path("timeout") {
          withRequestTimeout(timeout1) {
            extractRequestTimeout { t1 =>
              withRequestTimeout(timeout2) {
                extractRequestTimeout { t2 =>
                  complete(
                    if (t1 == timeout1 && t2 == timeout2) StatusCodes.OK
                    else StatusCodes.InternalServerError
                  )
                }
              }
            }
          }
        }
      //#extractRequestTimeout

      Get("/timeout") ~!> route ~> check {
        status should ===(StatusCodes.OK) // the timeout response
      }
    }
  }

}

class TimeoutDirectivesFiniteTimeoutExamplesSpec extends RoutingSpec
  with ScalaFutures with CompileOnlySpec {
  implicit val timeout: RouteTestTimeout = RouteTestTimeout(3.seconds)
  override def testConfigSource: String =
    "akka.http.server.request-timeout = 1000s\n" +
      super.testConfigSource

  def slowFuture(): Future[String] = Future.never

  // demonstrates that timeout is correctly modified for finite initial values of akka.http.server.request-timeout
  "Request Timeout" should {
    "be configurable in routing layer for finite initial value of request-timeout" in {
      val route =
        path("timeout") {
          withRequestTimeout(1.seconds) { // modifies the global akka.http.server.request-timeout for this request
            val response: Future[String] = slowFuture() // very slow
            complete(response)
          }
        }

      //check
      Get("/timeout") ~!> route ~> check {
        status should ===(StatusCodes.ServiceUnavailable) // the timeout response
      }
    }
  }

}
