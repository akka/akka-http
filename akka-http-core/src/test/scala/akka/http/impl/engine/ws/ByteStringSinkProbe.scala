/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.ws

import akka.NotUsed
import akka.actor.ActorSystem
import akka.annotation.InternalApi
import akka.stream.scaladsl.Sink
import akka.stream.testkit.TestSubscriber
import akka.util.ByteString

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
        probe.expectNoMsg(timeout)
      }

      var inBuffer = ByteString.empty
      @tailrec def expectBytes(length: Int): ByteString =
        if (inBuffer.size >= length) {
          val res = inBuffer.take(length)
          inBuffer = inBuffer.drop(length)
          res
        } else {
          inBuffer ++= probe.requestNext()
          expectBytes(length)
        }

      def expectBytes(expected: ByteString): Unit = {
        val got = expectBytes(expected.length)
        assert(got == expected, s"expected ${expected.length} bytes '$expected' but got ${got.length} bytes '$got'")
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
    }
}
