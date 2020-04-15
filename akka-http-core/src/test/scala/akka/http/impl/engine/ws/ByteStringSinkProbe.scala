/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.ws

import akka.NotUsed
import akka.actor.ActorSystem
import akka.annotation.InternalApi
import akka.stream.scaladsl.Sink
import akka.stream.testkit.TestSubscriber
import akka.util.{ ByteString, PrettyByteString }

import scala.annotation.tailrec
import scala.concurrent.duration.FiniteDuration

/** INTERNAL API */
@InternalApi
private[http] trait ByteStringSinkProbe {
  def sink: Sink[ByteString, NotUsed]

  def expectBytes(length: Int): ByteString
  def expectBytes(expected: ByteString): Unit

  def expectUtf8EncodedString(string: String): Unit

  def expectNoBytes(): Unit
  def expectNoBytes(timeout: FiniteDuration): Unit

  def expectSubscriptionAndComplete(): Unit
  def expectComplete(): Unit
  def expectError(): Throwable
  def expectError(cause: Throwable): Unit

  def ensureSubscription(): Unit
  def request(n: Long): Unit
  def cancel(): Unit

  def within[T](max: FiniteDuration)(f: => T): T
}

/** INTERNAL API */
@InternalApi
private[http] object ByteStringSinkProbe {
  def apply()(implicit system: ActorSystem): ByteStringSinkProbe =
    new ByteStringSinkProbe {
      val probe = TestSubscriber.probe[ByteString]()
      val sink: Sink[ByteString, NotUsed] = Sink.fromSubscriber(probe)

      def ensureRequested(): Unit = {
        probe.ensureSubscription()
        probe.request(1)
      }
      def expectNoBytes(): Unit = {
        ensureRequested()
        probe.expectNoMsg()
      }
      def expectNoBytes(timeout: FiniteDuration): Unit = {
        ensureRequested()
        probe.expectNoMessage(timeout)
      }

      var inBuffer = ByteString.empty
      @tailrec def expectBytes(length: Int): ByteString =
        if (inBuffer.size >= length) {
          val res = inBuffer.take(length)
          inBuffer = inBuffer.drop(length)
          res
        } else {
          try inBuffer ++= probe.requestNext()
          catch {
            case ex if ex.getMessage.contains("Expected OnNext") =>
              throw new AssertionError(
                s"Expected [$length] bytes but only got [${inBuffer.size}] bytes\n${PrettyByteString.asPretty(inBuffer).prettyPrint(1024)}"
              )
          }
          expectBytes(length)
        }

      def expectBytes(expected: ByteString): Unit = {
        val got = expectBytes(expected.length)
        val details =
          "Expected: \n" +
            PrettyByteString.asPretty(expected).prettyPrint(1024) +
            "\n" +
            "But got: \n" +
            PrettyByteString.asPretty(got).prettyPrint(1024)

        assert(got == expected, s"expected ${expected.length} bytes, but got ${got.length} bytes \n$details")
      }

      def expectUtf8EncodedString(expectedString: String): Unit = {
        val data = expectBytes(expectedString.getBytes("utf8").length).utf8String
        assert(data == expectedString, s"expected '$expectedString' but got '$data'")
      }

      def expectSubscriptionAndComplete(): Unit = probe.expectSubscriptionAndComplete()
      def expectComplete(): Unit = probe.expectComplete()
      def expectError(): Throwable = probe.expectError()
      def expectError(cause: Throwable): Unit = probe.expectError(cause)

      def ensureSubscription(): Unit = probe.ensureSubscription()
      def request(n: Long): Unit = probe.request(n)
      def cancel(): Unit = probe.cancel()

      def within[T](max: FiniteDuration)(f: => T): T = probe.within(max)(f)
    }
}
