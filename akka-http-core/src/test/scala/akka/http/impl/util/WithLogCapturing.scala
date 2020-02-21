/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.util

import java.io.{ OutputStream, PrintStream }

import akka.actor.ActorSystem
import akka.event.Logging
import akka.event.Logging._
import akka.testkit.{ EventFilter, TestEventListener }

import org.scalatest.{ Outcome, SuiteMixin, TestSuite }
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers

/**
 * Mixin this trait to a test to make log lines appear only when the test failed.
 */
trait WithLogCapturing extends SuiteMixin with Matchers with Eventually { this: TestSuite =>
  implicit def system: ActorSystem

  // When filtering just collects events into this var (yeah, it's a hack to do that in a filter).
  var events: List[LogEvent] = null

  abstract override def withFixture(test: NoArgTest): Outcome = {
    events = Nil
    object LogEventCollector extends EventFilter(Int.MaxValue) {
      override protected def matches(event: Logging.LogEvent): Boolean = {
        events ::= event
        true
      }
    }

    val myLogger = Logging(system, classOf[WithLogCapturing])
    val res = LogEventCollector.intercept {
      myLogger.debug(s"Logging started for test [${test.name}]")
      val r = test()
      myLogger.debug(s"Logging finished for test [${test.name}]")
      eventually { events.map(_.message) should contain(s"Logging finished for test [${test.name}]") }
      r
    }

    if (!(res.isSucceeded || res.isPending)) {
      println(s"--> [${Console.BLUE}${test.name}${Console.RESET}] Start of log messages of test that [$res]")
      val logger = new StdOutLogger {}
      withPrefixedOut("| ") {
        events.reverse.foreach(logger.print)
      }
      println(s"<-- [${Console.BLUE}${test.name}${Console.RESET}] End of log messages of test that [$res]")
    }
    res
  }

  def expectNoWarningsOrErrors(): Unit = {
    events should not be (null)
    events.find(e => e.level == WarningLevel || e.level == ErrorLevel) should be(empty)
    Thread.sleep(1000)
    events.find(e => e.level == WarningLevel || e.level == ErrorLevel) should be(empty)
  }

  /** Adds a prefix to every line printed out during execution of the thunk. */
  private def withPrefixedOut[T](prefix: String)(thunk: => T): T = {
    val oldOut = Console.out
    val prefixingOut =
      new PrintStream(new OutputStream {
        override def write(b: Int): Unit = oldOut.write(b)
      }) {
        override def println(x: Any): Unit =
          oldOut.println(prefix + String.valueOf(x).replaceAllLiterally("\n", s"\n$prefix"))
      }

    Console.withOut(prefixingOut) {
      thunk
    }
  }
}

/**
 * An adaption of TestEventListener that never prints debug logs itself. Use together with [[akka.http.impl.util.WithLogCapturing]].
 * It allows to enable noisy DEBUG logging for tests and silence pre/post test DEBUG output completely while still showing
 * test-specific DEBUG output selectively
 */
class DebugLogSilencingTestEventListener extends TestEventListener {
  override def print(event: Any): Unit = event match {
    case d: Debug => // ignore
    case _        => super.print(event)
  }
}

/**
 * An adaption of TestEventListener that does not print out any logs. Use together with [[akka.http.impl.util.WithLogCapturing]].
 * It allows to enable noisy logging for tests and silence pre/post test log output completely while still showing
 * test-specific log output selectively on failures.
 */
class SilenceAllTestEventListener extends TestEventListener {
  override def print(event: Any): Unit = ()
}
