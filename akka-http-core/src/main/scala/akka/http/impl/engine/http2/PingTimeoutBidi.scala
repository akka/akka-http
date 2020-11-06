package akka.http.impl.engine.http2

import akka.NotUsed
import akka.http.impl.engine.HttpIdleTimeoutException
import akka.http.impl.engine.http2.FrameEvent.PingFrame
import akka.http.scaladsl.settings.Http2ClientSettings
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

object PingTimeoutBidi extends {

  def apply(settings: Http2ClientSettings): BidiFlow[FrameEvent, FrameEvent, FrameEvent, FrameEvent, NotUsed] =
    BidiFlow.fromGraph(new PingTimeoutStage(settings))

  private val tick = "tick"
  private val Ping = PingFrame(false, ByteString("abcdefgh")) // FIXME should we have unique ping payloads so we can identify?

  // FIXME ignore ping/pong for the connection idle timeout? (by merging that logic into this stage perhaps?)
  // FIXME perhaps not quite related but, protection against something like CVE-2019-9512 (Ping Flood)
  private final class PingTimeoutStage(settings: Http2ClientSettings) extends GraphStage[BidiShape[FrameEvent, FrameEvent, FrameEvent, FrameEvent]] {
    val in1 = Inlet[FrameEvent]("in1")
    val out1 = Outlet[FrameEvent]("out1")
    val in2 = Inlet[FrameEvent]("in2")
    val out2 = Outlet[FrameEvent]("out2")
    val shape: BidiShape[FrameEvent, FrameEvent, FrameEvent, FrameEvent] = BidiShape.of(in1, out1, in2, out2)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new TimerGraphStageLogic(shape) {
      // FIXME this requires settings to be at least seconds, is that fine grained enough
      private val maxPingTimeNanos = settings.pingTimeout.toNanos
      private def maxPingsWithoutData: Long = settings.maxPingsWithoutData
      private val pingEveryNTickWithoutData = settings.pingTimeout.toSeconds
      private var ticksWithoutData = 0L
      private var lastPingTimestamp = 0L
      private var pingsWithoutData = 0L

      override def preStart() {
        // to limit overhead rather than constantly rescheduling a timer and looking at system time we use a constant timer
        // FIXME does that really matter (I think the idle timeout logic touches nanotime for every frame)?
        schedulePeriodically(tick, 1.second)
      }

      override protected def onTimer(timerKey: Any): Unit = {
        ticksWithoutData += 1L
        val now = System.nanoTime()
        if (lastPingTimestamp > 0L && (lastPingTimestamp - now) > maxPingTimeNanos) {
          // FIXME what fail action, should this rather trigger a GOAWAY?
          throw new HttpIdleTimeoutException(
            "HTTP/2 ping-timeout encountered, " +
              "no ping response within " + settings.pingTimeout + ". " + "This is configurable by akka.http2.client.idle-timeout.",
            settings.pingTimeout)
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
              if (maxPingsWithoutData > 0) {
                pingsWithoutData += 1L
                // FIXME fail with specific exception or some more graceful failure?
                if (pingsWithoutData > maxPingsWithoutData)
                  throw new RuntimeException("HTTP/2 more than " + maxPingsWithoutData + " pings exchanged without any data frames. This is configureable by akka.http2.client.max-pings-without-data")
              }
              push(out2, ack) // pass downstream for logging
            case other =>
              ticksWithoutData = 0
              if (maxPingsWithoutData > 0) pingsWithoutData = 0L
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
