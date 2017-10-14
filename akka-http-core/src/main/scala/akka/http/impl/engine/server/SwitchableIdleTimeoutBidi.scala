/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.server

import java.util.concurrent.TimeoutException

import akka.NotUsed
import akka.http.impl.engine.server.SwitchableIdleTimeoutBidi.{ DisableTimeout, EnableTimeout, TimeoutSwitch }
import akka.stream._
import akka.stream.impl.Stages.DefaultAttributes
import akka.stream.impl.Timers.GraphStageLogicTimer
import akka.stream.scaladsl.BidiFlow
import akka.stream.scaladsl.BidiFlow.fromGraph
import akka.stream.stage._

import scala.concurrent.duration.FiniteDuration

/**
 * A version of the [[akka.stream.impl.Timers.IdleTimeoutBidi]] that accepts, in one direction, elements that can
 * disable or re-enable the timeout.
 */
final class SwitchableIdleTimeoutBidi[I, O](val timeout: FiniteDuration) extends GraphStage[BidiShape[Either[TimeoutSwitch, I], I, O, O]] {
  val in1 = Inlet[Either[TimeoutSwitch, I]]("in1")
  val in2 = Inlet[O]("in2")
  val out1 = Outlet[I]("out1")
  val out2 = Outlet[O]("out2")
  val shape = BidiShape(in1, out1, in2, out2)

  override def initialAttributes = DefaultAttributes.idleTimeoutBidi

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new TimerGraphStageLogic(shape) {
    var timeoutOn: Boolean = true

    setHandlers(in1, out1, new InboundHandler)
    setHandlers(in2, out2, new OutboundHandler)

    final override def onTimer(key: Any): Unit =
      failStage(new TimeoutException(s"No elements passed in the last $timeout."))

    override def preStart(): Unit = resetTimer()

    private def resetTimer(): Unit = {
      if (timeoutOn) {
        scheduleOnce(GraphStageLogicTimer, timeout)
      } else {
        cancelTimer(GraphStageLogicTimer)
      }
    }

    class InboundHandler extends InHandler with OutHandler {
      override def onPush(): Unit = {
        grab(in1) match {
          case Left(EnableTimeout) ⇒
            timeoutOn = true
            resetTimer()
            pull(in1)
          case Left(DisableTimeout) ⇒
            timeoutOn = false
            resetTimer()
            pull(in1)
          case Right(elem) ⇒
            resetTimer()
            push(out1, elem)
        }
      }

      override def onPull(): Unit = pull(in1)

      override def onUpstreamFinish(): Unit = complete(out1)

      override def onDownstreamFinish(): Unit = cancel(in1)
    }

    class OutboundHandler extends InHandler with OutHandler {
      override def onPush(): Unit = {
        resetTimer()
        push(out2, grab(in2))
      }

      override def onPull(): Unit = pull(in2)

      override def onUpstreamFinish(): Unit = complete(out2)

      override def onDownstreamFinish(): Unit = cancel(in2)
    }
  }

  override def toString = "SwitchableIdleTimeoutBidi"
}

object SwitchableIdleTimeoutBidi {
  def bidirectionalSettableIdleTimeout[I, O](defaultTimeout: FiniteDuration): BidiFlow[Either[TimeoutSwitch, I], I, O, O, NotUsed] =
    fromGraph(new SwitchableIdleTimeoutBidi(defaultTimeout))

  sealed trait TimeoutSwitch

  case object DisableTimeout extends TimeoutSwitch
  case object EnableTimeout extends TimeoutSwitch
}
