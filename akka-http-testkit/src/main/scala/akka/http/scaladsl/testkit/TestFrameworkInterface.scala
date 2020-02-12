/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.testkit

import akka.http.scaladsl.server.ExceptionHandler
import org.scalatest.exceptions.TestFailedException
import org.scalatest.{ BeforeAndAfterAll, Suite }

//#source-quote
trait TestFrameworkInterface {

  def cleanUp()

  def failTest(msg: String): Nothing

  def testExceptionHandler: ExceptionHandler
}
//#source-quote

object TestFrameworkInterface {

  trait Scalatest extends TestFrameworkInterface with BeforeAndAfterAll {
    this: Suite =>

    def failTest(msg: String) = throw new TestFailedException(msg, 11)

    abstract override protected def afterAll(): Unit = {
      cleanUp()
      super.afterAll()
    }

    override val testExceptionHandler = ExceptionHandler {
      case e: org.scalatest.exceptions.TestFailedException => throw e
    }
  }
}

object Specs2FrameworkInterface {
  import org.specs2.execute.{ Failure, FailureException }
  import org.specs2.specification.AfterAll

  trait Specs2 extends TestFrameworkInterface with AfterAll {
    def failTest(msg: String): Nothing = throw new FailureException(Failure(msg))

    override def afterAll(): Unit = cleanUp()

    override val testExceptionHandler = ExceptionHandler {
      case e: org.specs2.execute.FailureException => throw e
    }
  }
}
