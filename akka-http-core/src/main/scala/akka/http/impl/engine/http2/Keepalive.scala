/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.NotUsed
import akka.http.impl.engine.HttpIdleTimeoutException
import akka.http.impl.engine.http2.FrameEvent.PingFrame
import akka.stream.Attributes
import akka.stream.BidiShape
import akka.stream.Inlet
import akka.stream.Outlet
import akka.stream.scaladsl.BidiFlow
import akka.stream.stage.GraphStage
import akka.stream.stage.GraphStageLogic
import akka.stream.stage.InHandler
import akka.stream.stage.OutHandler
import akka.stream.stage.TimerGraphStageLogic
import akka.util.ByteString

import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

object Keepalive {

  /**
   * @return a keepalive operator in client configuration (pings sent on out1 and expected acks coming back on in2)
   */
  def apply(keepaliveTime: FiniteDuration, keepaliveTimeout: FiniteDuration, maxKeepalivesWithoutData: Long): BidiFlow[FrameEvent, FrameEvent, FrameEvent, FrameEvent, NotUsed] =
    BidiFlow.fromGraph(new PingTimeoutStage(keepaliveTime, keepaliveTimeout, maxKeepalivesWithoutData))

  private val tick = "tick"
  private val Ping = PingFrame(false, ByteString("abcdefgh")) // FIXME should we have unique ping payloads so we can identify?

  // FIXME ignore ping/pong for the connection idle timeout? (by merging that logic into this stage perhaps?)
  // FIXME perhaps not quite related but, protection against something like CVE-2019-9512 (Ping Flood)
  private final class PingTimeoutStage(keepaliveTime: FiniteDuration, keepaliveTimeout: FiniteDuration, maxKeepalivesWithoutData: Long) extends GraphStage[BidiShape[FrameEvent, FrameEvent, FrameEvent, FrameEvent]] {
    val in1 = Inlet[FrameEvent]("in1")
    val out1 = Outlet[FrameEvent]("out1")
    val in2 = Inlet[FrameEvent]("in2")
    val out2 = Outlet[FrameEvent]("out2")
    val shape: BidiShape[FrameEvent, FrameEvent, FrameEvent, FrameEvent] = BidiShape.of(in1, out1, in2, out2)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new TimerGraphStageLogic(shape) {
      // FIXME this requires settings to be at least seconds, is that fine grained enough?
      private val maxKeepaliveTimeoutNanos = keepaliveTime.toNanos
      private val pingEveryNTickWithoutData = keepaliveTimeout.toSeconds
      private var ticksWithoutData = 0L
      private var lastPingTimestamp = 0L
      private var pingsWithoutData = 0L

      override def preStart(): Unit = {
        // to limit overhead rather than constantly rescheduling a timer and looking at system time we use a constant timer
        // FIXME does that really matter (I think the idle timeout logic touches nanotime for every frame)?
        schedulePeriodically(tick, 1.second)
      }

      override protected def onTimer(timerKey: Any): Unit = {
        ticksWithoutData += 1L
        val now = System.nanoTime()
        if (lastPingTimestamp > 0L && (lastPingTimestamp - now) > maxKeepaliveTimeoutNanos) {
          // FIXME what fail action, should this rather trigger a GOAWAY?
          throw new HttpIdleTimeoutException(
            "HTTP/2 ping-timeout encountered, " +
              "no ping response within " + keepaliveTimeout + ". " + "This is configurable by akka.http2.[client, server].keepalive-timeout.",
            keepaliveTimeout)
        }
        if (ticksWithoutData > 0L && ticksWithoutData % pingEveryNTickWithoutData == 0) {
          // FIXME only emit when there are active calls/streams?
          lastPingTimestamp = now
          emit(out1, Ping)
        }
      }

      setHandlers(in1, out1, new InHandler with OutHandler {
        override def onPush(): Unit = {
          push(out1, grab(in1))
        }
        override def onPull(): Unit = {
          if (!hasBeenPulled(in1)) pull(in1)
        }
      })
      setHandlers(in2, out2, new InHandler with OutHandler {
        override def onPush(): Unit = {
          val frame = grab(in2)
          frame match {
            case ack @ PingFrame(true, _) =>
              // FIXME here we could collect ping latency/log or something
              lastPingTimestamp = 0L
              if (maxKeepalivesWithoutData > 0) {
                pingsWithoutData += 1L
                // FIXME fail with specific exception or some more graceful failure?
                if (pingsWithoutData > maxKeepalivesWithoutData)
                  throw new RuntimeException("HTTP/2 more than " + maxKeepalivesWithoutData + " pings exchanged without any data frames. This is configureable by akka.http2.[client, server].max-keepalives-without-data")
              }
              push(out2, ack) // pass downstream for logging
            case other =>
              ticksWithoutData = 0
              if (maxKeepalivesWithoutData > 0) pingsWithoutData = 0L
              push(out2, other)
          }

        }
        override def onPull(): Unit = {
          pull(in2)
        }
      })
    }
  }

}
