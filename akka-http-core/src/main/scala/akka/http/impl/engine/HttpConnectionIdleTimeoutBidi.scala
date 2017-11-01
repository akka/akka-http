/*
 * Copyright (C) 2015-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine

import java.util.concurrent.{ TimeUnit, TimeoutException }

import akka.annotation.InternalApi
import akka.dispatch.ExecutionContexts
import akka.http.impl.engine.server.HttpAttributes
import akka.stream._
import akka.stream.scaladsl.{ BidiFlow, Flow, Keep }
import akka.stream.stage._
import akka.util.ByteString
import akka.{ Done, NotUsed }

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ Future, Promise }
import scala.util.control.NoStackTrace

/** INTERNAL API */
@InternalApi
private[akka] object HttpConnectionIdleTimeoutBidi {
  def apply(idleTimeout: FiniteDuration, server: Boolean): BidiFlow[ByteString, ByteString, ByteString, ByteString, NotUsed] = {
    val idleDetector = new HttpIdleStage[ByteString](idleTimeout, server)
    val connectionKiller = Flow.fromGraph(KillSwitches.single[ByteString])

    BidiFlow.fromFlowsMat(
      idleDetector,
      connectionKiller.viaMat(idleDetector)(Keep.both)
    ) {
        case (outIdleFuture, (killSwitch, inIdleFuture)) ⇒
          val killTheConnection: PartialFunction[Throwable, Unit] = {
            case ex: HttpIdleTimeoutException ⇒ killSwitch.abort(ex)
          }
          outIdleFuture.onFailure(killTheConnection)(ExecutionContexts.sameThreadExecutionContext)
          inIdleFuture.onFailure(killTheConnection)(ExecutionContexts.sameThreadExecutionContext)

          NotUsed
      }
  }

  private object IdleTick

  // Custom idle stage for HTTP that instead of failing the stream completes a promise upon timeout
  // so that we can make sure we fail the stream from the source and avoid a race where cancel reaches
  // the user logic first, issue #1026
  private class HttpIdleStage[T](timeout: FiniteDuration, server: Boolean) extends GraphStageWithMaterializedValue[FlowShape[T, T], Future[Done]] {
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

    def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[Done]) = {
      val failPromise = Promise[Done]()
      val logic = new TimerGraphStageLogic(shape) with InHandler with OutHandler {

        private var nextDeadline: Long = System.nanoTime + timeoutNanos
        private def onActivity(): Unit = nextDeadline = System.nanoTime + timeoutNanos
        override def preStart(): Unit = schedulePeriodically(IdleTick, idleCheckInterval)

        override def onPush(): Unit = {
          onActivity()
          push(out, grab(in))
        }

        final override def onTimer(key: Any): Unit =
          if (nextDeadline - System.nanoTime < 0) {
            failPromise.tryFailure(idleTimeoutException)
            cancelTimer(IdleTick)
          }

        override def onPull(): Unit = pull(in)

        def idleTimeoutException = {
          val connectionToString = inheritedAttributes.get[HttpAttributes.RemoteAddress] match {
            case Some(addr) ⇒ s" on connection to [${addr.address}]"
            case _          ⇒ ""
          }

          new HttpIdleTimeoutException(
            s"HTTP idle-timeout encountered$connectionToString, no bytes passed in the last $timeout. " +
              s"This is configurable by [akka.http.${if (server) "server" else "client"}.idle-timeout]", timeout)
        }

        setHandlers(in, out, this)
      }
      (logic, failPromise.future)
    }

    override def toString = s"HttpIdleStage($timeout)"
  }

}

class HttpIdleTimeoutException(msg: String, timeout: FiniteDuration) extends TimeoutException(msg: String) with NoStackTrace
