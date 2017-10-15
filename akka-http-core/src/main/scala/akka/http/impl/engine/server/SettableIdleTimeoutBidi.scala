/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.server

import java.util.concurrent.TimeUnit

import akka.NotUsed
import akka.annotation.InternalApi
import akka.http.impl.engine.HttpIdleTimeoutException
import akka.http.impl.engine.server.SettableIdleTimeoutBidi._
import akka.http.scaladsl.model.headers.{ CustomHeader, InternalCustomHeader }
import akka.stream._
import akka.stream.impl.Stages.DefaultAttributes
import akka.stream.impl.Timers.GraphStageLogicTimer
import akka.stream.scaladsl.BidiFlow
import akka.stream.scaladsl.BidiFlow.fromGraph
import akka.stream.stage._

import scala.concurrent.duration.Duration.Infinite
import scala.concurrent.duration.{ Duration, FiniteDuration }

/**
 * A version of the [[akka.stream.impl.Timers.IdleTimeoutBidi]] that accepts, in one direction, elements that can
 * modify or reset the timeout.
 */

/** INTERNAL API */
@InternalApi
private[http] final class SettableIdleTimeoutBidi[I, O](val defaultTimeout: Duration) extends GraphStage[BidiShape[OrTimeoutSetting[I], I, O, O]] {
  val in1 = Inlet[OrTimeoutSetting[I]]("in1")
  val in2 = Inlet[O]("in2")
  val out1 = Outlet[I]("out1")
  val out2 = Outlet[O]("out2")
  val shape = BidiShape(in1, out1, in2, out2)

  override def initialAttributes = DefaultAttributes.idleTimeoutBidi

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new TimerGraphStageLogic(shape) {
    private var currentTimeout: Duration = defaultTimeout
    private var nextDeadline: Option[Long] = buildNextDeadline()

    setHandlers(in1, out1, new InboundHandler)
    setHandlers(in2, out2, new OutboundHandler)

    private def onActivity(): Unit = nextDeadline = buildNextDeadline()

    private def buildNextDeadline(): Option[Long] = currentTimeout match {
      case fd: FiniteDuration ⇒
        Some(System.nanoTime + fd.toNanos)
      case _ ⇒
        None
    }

    final override def onTimer(key: Any): Unit = nextDeadline match {
      case Some(deadline) if deadline - System.nanoTime < 0 ⇒
        currentTimeout match {
          case fd: FiniteDuration ⇒
            failStage(new HttpIdleTimeoutException(
              "HTTP idle-timeout encountered, no bytes passed in the last " + fd +
                ". This is configurable by akka.http.server.idle-timeout.", fd))
          case _ ⇒ // If the timeout is set to infinite, we should never get a timeout anyway.
        }
      case _ ⇒ // Either no deadline is set, or it hasn't been reached.
    }

    override def preStart(): Unit = resetTimer()

    private def resetTimer(): Unit = {
      currentTimeout match {
        case fd: FiniteDuration ⇒
          schedulePeriodically(GraphStageLogicTimer, idleTimeoutCheckInterval(fd))
        case _: Infinite ⇒
          cancelTimer(GraphStageLogicTimer)
      }
    }

    class InboundHandler extends InHandler with OutHandler {
      override def onPush(): Unit = {
        grab(in1) match {
          case Left(SetTimeout(timeout)) ⇒
            currentTimeout = timeout
            resetTimer()
            pull(in1)
          case Left(ResetTimeout) ⇒
            currentTimeout = defaultTimeout
            resetTimer()
            pull(in1)
          case Right(elem) ⇒
            onActivity()
            push(out1, elem)
        }
      }

      override def onPull(): Unit = pull(in1)

      override def onUpstreamFinish(): Unit = complete(out1)

      override def onDownstreamFinish(): Unit = cancel(in1)
    }

    class OutboundHandler extends InHandler with OutHandler {
      override def onPush(): Unit = {
        onActivity()
        push(out2, grab(in2))
      }

      override def onPull(): Unit = pull(in2)

      override def onUpstreamFinish(): Unit = complete(out2)

      override def onDownstreamFinish(): Unit = cancel(in2)
    }
  }

  override def toString = "SwitchableIdleTimeoutBidi"
}

object SettableIdleTimeoutBidi {
  private def idleTimeoutCheckInterval(timeout: FiniteDuration): FiniteDuration = {
    import scala.concurrent.duration._
    FiniteDuration(
      math.min(math.max(timeout.toNanos / 8, 100.millis.toNanos), timeout.toNanos / 2),
      TimeUnit.NANOSECONDS)
  }

  def bidirectionalSettableIdleTimeout[I, O](defaultTimeout: Duration): BidiFlow[OrTimeoutSetting[I], I, O, O, NotUsed] =
    fromGraph(new SettableIdleTimeoutBidi(defaultTimeout))

  type OrTimeoutSetting[Other] = Either[TimeoutSetting, Other]

  sealed trait TimeoutSetting
  case class SetTimeout(timeout: Duration) extends TimeoutSetting
  case object ResetTimeout extends TimeoutSetting

  private[http] case class SetIdleTimeoutHeader(timeout: Duration) extends InternalCustomHeader("SetIdleTimeoutHeader")
}
