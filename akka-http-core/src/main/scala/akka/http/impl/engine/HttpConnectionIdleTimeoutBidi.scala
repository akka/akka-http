/*
 * Copyright (C) 2015-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine

import java.net.InetSocketAddress
import java.util.concurrent.{ TimeUnit, TimeoutException }

import akka.NotUsed
import akka.annotation.InternalApi
import akka.dispatch.ExecutionContexts
import akka.http.impl.engine.server.HttpAttributes
import akka.stream._
import akka.stream.scaladsl.{ BidiFlow, Flow }
import akka.stream.stage._
import akka.util.ByteString

import scala.concurrent.Promise
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NoStackTrace

/** INTERNAL API */
@InternalApi
private[akka] object HttpConnectionIdleTimeoutBidi {
  def apply(idleTimeout: FiniteDuration): BidiFlow[ByteString, ByteString, ByteString, ByteString, NotUsed] = {
    val killSwitchP = Promise[UniqueKillSwitch]()
    val connectionKiller = Flow.fromGraph(KillSwitches.single[ByteString]).mapMaterializedValue { switch ⇒
      killSwitchP.success(switch)
      NotUsed
    }
    val idleCallback = (remoteAddress: Option[InetSocketAddress]) ⇒ {
      killSwitchP.future.foreach { killSwitch ⇒
        val connectionToString = remoteAddress match {
          case Some(addr) ⇒ s" on connection to [$addr]"
          case _          ⇒ ""
        }
        killSwitch.abort(new HttpIdleTimeoutException(
          "HTTP idle-timeout encountered" + connectionToString + ", " +
            "no bytes passed in the last " + idleTimeout + ". " +
            "This is configurable by akka.http.[server|client].idle-timeout.", idleTimeout))
      }(ExecutionContexts.sameThreadExecutionContext)
    }
    val idleDetector = new HttpIdleStage[ByteString](idleTimeout, idleCallback)

    BidiFlow.fromFlows(
      connectionKiller.via(idleDetector),
      idleDetector
    )
  }

  private object IdleTick

  // Custom idle stage for HTTP that instead of failing the stream completes a promise upon timeout
  // so that we can make sure we fail the stream from the source and avoid a race where cancel reaches
  // the user logic first, issue #1026
  private class HttpIdleStage[T](timeout: FiniteDuration, onIdle: (Option[InetSocketAddress]) ⇒ Unit) extends GraphStage[FlowShape[T, T]] {
    val in = Inlet[T]("HttpIdleStage")
    val out = Outlet[T]("HttpIdleStage")
    override val shape = FlowShape[T, T](in, out)
    private val timeoutNanos = timeout.toNanos
    private val idleCheckInterval = {
      import scala.concurrent.duration._
      FiniteDuration(
        math.min(math.max(timeoutNanos / 8, 100.millis.toNanos), timeoutNanos / 2),
        TimeUnit.NANOSECONDS)
    }

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new TimerGraphStageLogic(shape) with InHandler with OutHandler {
      private var nextDeadline: Long = System.nanoTime + timeoutNanos
      private def onActivity(): Unit = nextDeadline = System.nanoTime + timeoutNanos
      override def preStart(): Unit = schedulePeriodically(IdleTick, idleCheckInterval)

      override def onPush(): Unit = {
        onActivity()
        push(out, grab(in))
      }

      final override def onTimer(key: Any): Unit =
        if (nextDeadline - System.nanoTime < 0) {
          onIdle(inheritedAttributes.get[HttpAttributes.RemoteAddress].map(_.address))
          cancelTimer(IdleTick)
        }

      override def onPull(): Unit = pull(in)

      setHandlers(in, out, this)
    }

    override def toString = s"HttpIdleStage($timeout)"
  }

}

class HttpIdleTimeoutException(msg: String, timeout: FiniteDuration) extends TimeoutException(msg: String) with NoStackTrace
