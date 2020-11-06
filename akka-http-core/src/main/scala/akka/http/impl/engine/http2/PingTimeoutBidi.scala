package akka.http.impl.engine.http2

import akka.NotUsed
import akka.http.impl.engine.http2.FrameEvent.PingFrame
import akka.http.impl.engine.ws.Protocol.Opcode.Ping
import akka.http.impl.engine.ws.Protocol.Opcode.Ping
import akka.http.impl.engine.ws.Protocol.Opcode.Ping
import akka.http.impl.engine.ws.Protocol.Opcode.Ping
import akka.http.impl.engine.ws.Protocol.Opcode.Ping
import akka.http.scaladsl.settings.Http2ClientSettings
import akka.stream
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
      private val maxPingsWithoutResponse = settings.pingTimeout.toSeconds
      private val pingEveryNTickWithoutData = settings.pingTimeout.toSeconds
      private var ticksWithoutData = 0L
      private var outstandingPings = 0L

      // to limit overhead rather than constantly rescheduling a timer and looking at system time we use a constant timer
      // FIXME does that really matter (I think the idle timeout logic touches nanotime for every frame)?
      schedulePeriodically(tick, 1.second)

      override protected def onTimer(timerKey: Any): Unit = {
        ticksWithoutData += 1L
        if (outstandingPings > maxPingsWithoutResponse) {
          ??? // FIXME what fail action, should this trigger a GOAWAY?
        }
        if (ticksWithoutData % pingEveryNTickWithoutData == 0) {
          // FIXME only emit when there are active calls/streams?
          emit(out1, Ping)
          outstandingPings += 1
        }
      }

      setHandlers(in1, out1, new InHandler with OutHandler {
        override def onPush(): Unit = {
          push(out1, grab(in1))
        }
        override def onPull(): Unit = {
          pull(in1)
        }
      })
      setHandlers(in2, out2, new InHandler with OutHandler {
        override def onPush(): Unit = {
          val frame = grab(in2)
          frame match {
            case PingFrame(true, _) =>
              // FIXME should we even reset here rather, or else a missed response will stay missed forever
              // do we need something more elaborate than a counter - unique ping messages and a timestamp for each/or ordering they were sent?
              outstandingPings -= 1
            case other =>
              ticksWithoutData = 0 // reset on any data, not only pings?
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
