/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.server
package directives

import akka.http.scaladsl.model.StatusCodes
import akka.pattern.CircuitBreaker

import scala.concurrent.Future
import akka.testkit._
import org.scalatest.Inside

import scala.concurrent.duration._

class FutureDirectivesSpec extends RoutingSpec with Inside with TestKitBase {

  class TestException(msg: String) extends Exception(msg)
  object TestException extends Exception("XXX")
  def throwTestException[T](msgPrefix: String): T => Nothing = t => throw new TestException(msgPrefix + t)

  val showEx = handleExceptions(ExceptionHandler {
    case e: TestException => complete(StatusCodes.InternalServerError, "Oops. " + e)
  })

  trait TestWithCircuitBreaker {
    val breakerResetTimeout = 500.millis.dilated
    val breaker = new CircuitBreaker(system.scheduler, maxFailures = 1, callTimeout = 10.seconds.dilated, breakerResetTimeout)
    def openBreaker() = breaker.withCircuitBreaker(Future.failed(new Exception("boom")))
  }

  "The `onComplete` directive" should {
    "unwrap a Future in the success case" in {
      var i = 0
      def nextNumber() = { i += 1; i }
      val route = onComplete(Future.successful(nextNumber())) { echoComplete }
      Get() ~> route ~> check {
        responseAs[String] shouldEqual "Success(1)"
      }
      Get() ~> route ~> check {
        responseAs[String] shouldEqual "Success(2)"
      }
    }
    "unwrap a Future in the failure case" in {
      Get() ~> onComplete(Future.failed[String](new RuntimeException("no"))) { echoComplete } ~> check {
        responseAs[String] shouldEqual "Failure(java.lang.RuntimeException: no)"
      }
    }
    "catch an exception in the success case" in {
      Get() ~> showEx(onComplete(Future.successful("ok")) { throwTestException("EX when ") }) ~> check {
        status shouldEqual StatusCodes.InternalServerError
        responseAs[String] shouldEqual s"Oops. akka.http.scaladsl.server.directives.FutureDirectivesSpec$$TestException: EX when Success(ok)"
      }
    }
    "catch an exception in the failure case" in {
      Get() ~> showEx(onComplete(Future.failed[String](new RuntimeException("no"))) { throwTestException("EX when ") }) ~> check {
        status shouldEqual StatusCodes.InternalServerError
        responseAs[String] shouldEqual s"Oops. akka.http.scaladsl.server.directives.FutureDirectivesSpec$$TestException: EX when Failure(java.lang.RuntimeException: no)"
      }
    }
  }

  "The `onCompleteWithBreaker` directive" should {
    "unwrap a Future in the success case" in new TestWithCircuitBreaker {
      var i = 0
      def nextNumber() = { i += 1; i }
      val route = onCompleteWithBreaker(breaker)(Future.successful(nextNumber())) { echoComplete }
      Get() ~> route ~> check {
        responseAs[String] shouldEqual "Success(1)"
      }
      Get() ~> route ~> check {
        responseAs[String] shouldEqual "Success(2)"
      }
    }
    "unwrap a Future in the failure case" in new TestWithCircuitBreaker {
      Get() ~> onCompleteWithBreaker(breaker)(Future.failed[String](new RuntimeException("no"))) { echoComplete } ~> check {
        responseAs[String] shouldEqual "Failure(java.lang.RuntimeException: no)"
      }
    }
    "fail fast if the circuit breaker is open" in new TestWithCircuitBreaker {
      openBreaker()
      // since this is timing sensitive, try a few times to observe the breaker open
      awaitAssert(
        Get() ~> onCompleteWithBreaker(breaker)(Future.successful(1)) { echoComplete } ~> check {
          inside(rejection) {
            case CircuitBreakerOpenRejection(_) =>
          }
        }, breakerResetTimeout / 2)
    }
    "stop failing fast when the circuit breaker closes" in new TestWithCircuitBreaker {
      openBreaker()
      // observe that it opened
      awaitAssert(breaker.isOpen should ===(true))
      // since this is timing sensitive, try a few times to observe the breaker closed
      awaitAssert({
        Get() ~> onCompleteWithBreaker(breaker)(Future.successful(1)) { echoComplete } ~> check {
          responseAs[String] shouldEqual "Success(1)"
        }
      }, breakerResetTimeout + 1.second)
    }
    "catch an exception in the success case" in new TestWithCircuitBreaker {
      Get() ~> showEx(onCompleteWithBreaker(breaker)(Future.successful("ok")) { throwTestException("EX when ") }) ~> check {
        status shouldEqual StatusCodes.InternalServerError
        responseAs[String] shouldEqual s"Oops. akka.http.scaladsl.server.directives.FutureDirectivesSpec$$TestException: EX when Success(ok)"
      }
    }
    "catch an exception in the failure case" in new TestWithCircuitBreaker {
      Get() ~> showEx(onCompleteWithBreaker(breaker)(Future.failed[String](new RuntimeException("no"))) { throwTestException("EX when ") }) ~> check {
        status shouldEqual StatusCodes.InternalServerError
        responseAs[String] shouldEqual s"Oops. akka.http.scaladsl.server.directives.FutureDirectivesSpec$$TestException: EX when Failure(java.lang.RuntimeException: no)"
      }
    }
  }

  "The `onSuccess` directive" should {
    "unwrap a Future in the success case" in {
      Get() ~> onSuccess(Future.successful("yes")) { echoComplete } ~> check {
        responseAs[String] shouldEqual "yes"
      }
    }
    "propagate the exception in the failure case" in EventFilter[TestException.type](
      occurrences = 1,
      message = BasicRouteSpecs.defaultExnHandler500Error("XXX")
    ).intercept {
        Get() ~> onSuccess(Future.failed[Int](TestException)) { echoComplete } ~> check {
          status shouldEqual StatusCodes.InternalServerError
        }
      }
    "catch an exception in the success case" in {
      Get() ~> showEx(onSuccess(Future.successful("ok")) { throwTestException("EX when ") }) ~> check {
        status shouldEqual StatusCodes.InternalServerError
        responseAs[String] shouldEqual s"Oops. akka.http.scaladsl.server.directives.FutureDirectivesSpec$$TestException: EX when ok"
      }
    }
    "catch an exception in the failure case" in EventFilter[TestException.type](
      occurrences = 1,
      message = BasicRouteSpecs.defaultExnHandler500Error("XXX")
    ).intercept {
        Get() ~> onSuccess(Future.failed[Unit](TestException)) { throwTestException("EX when ") } ~> check {
          status shouldEqual StatusCodes.InternalServerError
          responseAs[String] shouldEqual "There was an internal server error."
        }
      }
  }

  "The `completeOrRecoverWith` directive" should {
    "complete the request with the Future's value if the future succeeds" in {
      Get() ~> completeOrRecoverWith(Future.successful("yes")) { echoComplete } ~> check {
        responseAs[String] shouldEqual "yes"
      }
    }
    "don't call the inner route if the Future succeeds" in {
      Get() ~> completeOrRecoverWith(Future.successful("ok")) { throwTestException("EX when ") } ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[String] shouldEqual "ok"
      }
    }
    "recover using the inner route if the Future fails" in {
      val route = completeOrRecoverWith(Future.failed[String](TestException)) {
        case e => complete(s"Exception occurred: ${e.getMessage}")
      }

      Get() ~> route ~> check {
        responseAs[String] shouldEqual "Exception occurred: XXX"
      }
    }
    "catch an exception during recovery" in {
      Get() ~> showEx(completeOrRecoverWith(Future.failed[String](TestException)) { throwTestException("EX when ") }) ~> check {
        status shouldEqual StatusCodes.InternalServerError
        responseAs[String] shouldEqual s"Oops. akka.http.scaladsl.server.directives.FutureDirectivesSpec$$TestException: EX when akka.http.scaladsl.server.directives.FutureDirectivesSpec$$TestException$$: XXX"
      }
    }
  }
}
