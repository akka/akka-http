/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.util

import akka.http.scaladsl.util.FastFuture._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future, Promise }
import scala.util.control.NoStackTrace
import scala.util.{ Failure, Success, Try }
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class FastFutureSpec extends AnyFreeSpec with Matchers {
  object TheException extends RuntimeException("Expected exception") with NoStackTrace

  "FastFuture should implement" - {
    "transformWith(Try => Future)" - {
      "Success -> Success" in {
        test(Success(23), _.transformWith(t => FastFuture(t.map(_ + 19)))) {
          _ shouldEqual Success(42)
        }
      }
      "Success -> Failure" in {
        test(Success(23), _.transformWith(_ => FastFuture.failed(TheException))) {
          _ shouldEqual Failure(TheException)
        }
      }
      "Failure -> Success" in {
        test(Failure(TheException), _.transformWith(t => FastFuture.successful(23))) {
          _ shouldEqual Success(23)
        }
      }
      "Failure -> Failure" in {
        test(Failure(TheException), _.transformWith(_ => FastFuture.failed(TheException))) {
          _ shouldEqual Failure(TheException)
        }
      }
      "Success -> user-function failed" in {
        test(Success(23), _.transformWith(failF)) {
          _ shouldEqual Failure(TheException)
        }
      }
      "Failure -> user-function failed" in {
        test(Failure(TheException), _.transformWith(failF)) {
          _ shouldEqual Failure(TheException)
        }
      }
    }
    "transformWith(A => Future[B], Throwable => Future[B])" - {
      "Success -> Success" in {
        test(Success(23), _.transformWith(t => FastFuture.successful(t + 19), neverCalled)) {
          _ shouldEqual Success(42)
        }
      }
      "Success -> Failure" in {
        test(Success(23), _.transformWith(_ => FastFuture.failed(TheException), neverCalled)) {
          _ shouldEqual Failure(TheException)
        }
      }
      "Failure -> Success" in {
        test(Failure(TheException), _.transformWith(neverCalled, t => FastFuture.successful(23))) {
          _ shouldEqual Success(23)
        }
      }
      "Failure -> Failure" in {
        test(Failure(TheException), _.transformWith(neverCalled, _ => FastFuture.failed(TheException))) {
          _ shouldEqual Failure(TheException)
        }
      }
      "Success -> user-function failed" in {
        test(Success(23), _.transformWith(failF, neverCalled)) {
          _ shouldEqual Failure(TheException)
        }
      }
      "Failure -> user-function failed" in {
        test(Failure(TheException), _.transformWith(neverCalled, failF)) {
          _ shouldEqual Failure(TheException)
        }
      }
    }
    "map" - {
      "map success" in {
        test(Success(23), _.map(_ + 19)) {
          _ shouldEqual Success(42)
        }
      }
      "report exceptions from user function" in {
        test(Success(23), _.map(failF)) {
          _ shouldEqual Failure(TheException)
        }
      }
      "propagate errors" in {
        test(Failure(TheException), _.map(neverCalled)) {
          _ shouldEqual Failure(TheException)
        }
      }
    }
    "flatMap" - {
      "both success" in {
        test(Success(23), _.flatMap(i => FastFuture.successful(i + 19))) {
          _ shouldEqual Success(42)
        }
      }
      "outer failure" in {
        test(Failure(TheException), _.flatMap(neverCalled)) {
          _ shouldEqual Failure(TheException)
        }
      }
      "inner failure" in {
        test(Success(23), _.flatMap(i => FastFuture.failed(TheException))) {
          _ shouldEqual Failure(TheException)
        }
      }
      "user-func failure" in {
        test(Success(23), _.flatMap(failF)) {
          _ shouldEqual Failure(TheException)
        }
      }
    }
    "recoverWith" - {
      "Success" in {
        test(Success(23), _.recoverWith(neverCalled)) {
          _ shouldEqual Success(23)
        }
      }
      "Failure -> Success" in {
        test(Failure(UnexpectedException), _.recoverWith { case _ => FastFuture.successful(23) }) {
          _ shouldEqual Success(23)
        }
      }
      "Failure -> Failure" in {
        test(Failure(UnexpectedException), _.recoverWith { case _ => FastFuture.failed(TheException) }) {
          _ shouldEqual Failure(TheException)
        }
      }
      "user-function failed" in {
        test(Failure(UnexpectedException), _.recoverWith(failF)) {
          _ shouldEqual Failure(TheException)
        }
      }
    }
    "recover" - {
      "Success" in {
        test(Success(23), _.recover(neverCalled)) {
          _ shouldEqual Success(23)
        }
      }
      "Failure -> Success" in {
        test(Failure(UnexpectedException), _.recover { case _ => 23 }) {
          _ shouldEqual Success(23)
        }
      }
      "user-function failed" in {
        test(Failure(UnexpectedException), _.recoverWith(failF)) {
          _ shouldEqual Failure(TheException)
        }
      }
    }
  }

  def test(result: Try[Int], op: FastFuture[Int] => Future[Int])(check: Try[Int] => Unit): Unit = {
    def testStrictly(): Unit = {
      val f = FastFuture(result)
      check(op(f.fast).value.get)
    }
    def testLazily(): Unit = {
      val p = Promise[Int]()
      val opped = op(p.future.fast)
      p.complete(result)
      Await.ready(opped, 500.millis)
      check(opped.value.get)
    }
    testStrictly()
    testLazily()
  }

  def failF: PartialFunction[Any, Nothing] = {
    case _ => throw TheException
  }
  class UnexpectedException extends RuntimeException("Unexpected exception - should never happen")
  object UnexpectedException extends UnexpectedException with NoStackTrace
  def neverCalled: PartialFunction[Any, Nothing] = {
    case _ => throw new UnexpectedException
  }
}
