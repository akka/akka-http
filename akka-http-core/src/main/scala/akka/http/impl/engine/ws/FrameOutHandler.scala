/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.ws

import akka.NotUsed
import akka.event.LoggingAdapter
import akka.http.impl.engine.ws.FrameHandler.{ UserHandlerErredOut, _ }
import akka.http.impl.engine.ws.WebSocket.Tick
import akka.http.impl.util.Timestamp
import akka.stream.scaladsl.Flow
import akka.stream.stage._
import akka.stream.{ Attributes, FlowShape, Inlet, Outlet }

import scala.concurrent.duration.FiniteDuration

/**
 * Implements the transport connection close handling at the end of the pipeline.
 *
 * INTERNAL API
 */
private[http] class FrameOutHandler(serverSide: Boolean, _closeTimeout: FiniteDuration, log: LoggingAdapter)
  extends GraphStage[FlowShape[FrameOutHandler.Input, FrameStart]] {
  val handlerInputsIn = Inlet[FrameOutHandler.Input]("FrameOutHandler.handlerInputsIn")
  val frameStartsOut = Outlet[FrameStart]("FrameOutHandler.frameStartsOut")

  override def shape = FlowShape(handlerInputsIn, frameStartsOut)

  private def closeTimeout: Timestamp = Timestamp.now + _closeTimeout

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {

    private def absorbTermination() =
      if (isAvailable(frameStartsOut)) getHandler(frameStartsOut).onPull()

    private object Idle extends InHandler with ProcotolExceptionHandling {
      override def onPush() =
        grab(handlerInputsIn) match {
          case start: FrameStart   ⇒ push(frameStartsOut, start)
          case DirectAnswer(frame) ⇒ push(frameStartsOut, frame)
          case PeerClosed(code, reason) if !code.exists(Protocol.CloseCodes.isError) ⇒
            // let user complete it, FIXME: maybe make configurable? immediately, or timeout
            setHandler(handlerInputsIn, new WaitingForUserHandlerClosed(FrameEvent.closeFrame(code.getOrElse(Protocol.CloseCodes.Regular), reason)))
            pull(handlerInputsIn)
          case PeerClosed(code, reason) ⇒
            val closeFrame = FrameEvent.closeFrame(code.getOrElse(Protocol.CloseCodes.Regular), reason)
            if (serverSide) {
              push(frameStartsOut, closeFrame)
              completeStage()
            } else {
              setHandler(handlerInputsIn, new WaitingForTransportClose)
              push(frameStartsOut, closeFrame)
            }
          case ActivelyCloseWithCode(code, reason) ⇒
            val closeFrame = FrameEvent.closeFrame(code.getOrElse(Protocol.CloseCodes.Regular), reason)
            setHandler(handlerInputsIn, new WaitingForPeerCloseFrame())
            push(frameStartsOut, closeFrame)
          case UserHandlerCompleted ⇒
            setHandler(handlerInputsIn, new WaitingForPeerCloseFrame())
            push(frameStartsOut, FrameEvent.closeFrame(Protocol.CloseCodes.Regular))
          case UserHandlerErredOut(e) ⇒
            log.error(e, s"Websocket handler failed with ${e.getMessage}")
            setHandler(handlerInputsIn, new WaitingForPeerCloseFrame())
            push(frameStartsOut, FrameEvent.closeFrame(Protocol.CloseCodes.UnexpectedCondition, "internal error"))
          case Tick ⇒ pull(handlerInputsIn) // ignore
        }

      override def onUpstreamFinish(): Unit = {
        becomeSendOutCloseFrameAndComplete(FrameEvent.closeFrame(Protocol.CloseCodes.Regular))
        absorbTermination()
      }
    }

    /**
     * peer has closed, we want to wait for user handler to close as well
     */
    private class WaitingForUserHandlerClosed(closeFrame: FrameStart) extends InHandler {
      def onPush() =
        grab(handlerInputsIn) match {
          case UserHandlerCompleted ⇒ sendOutLastFrame()
          case UserHandlerErredOut(e) ⇒
            log.error(e, s"Websocket handler failed while waiting for handler completion with ${e.getMessage}")
            sendOutLastFrame()
          case start: FrameStart ⇒ push(frameStartsOut, start)
          case _                 ⇒ pull(handlerInputsIn) // ignore
        }

      private def sendOutLastFrame(): Unit =
        if (serverSide) {
          push(frameStartsOut, closeFrame)
          completeStage()
        } else {
          setHandler(handlerInputsIn, new WaitingForTransportClose())
          push(frameStartsOut, closeFrame)
        }

      override def onUpstreamFinish(): Unit =
        fail(frameStartsOut, new IllegalStateException("Mustn't complete before user has completed"))
    }

    /**
     * we have sent out close frame and wait for peer to sent its close frame
     */
    private class WaitingForPeerCloseFrame(timeout: Timestamp = closeTimeout) extends InHandler with ProcotolExceptionHandling {
      override def onPush() =
        grab(handlerInputsIn) match {
          case Tick ⇒
            if (timeout.isPast) completeStage()
            else pull(handlerInputsIn)
          case PeerClosed(code, reason) ⇒
            if (serverSide) completeStage()
            else {
              setHandler(handlerInputsIn, new WaitingForTransportClose())
              pull(handlerInputsIn)
            }
          case _ ⇒ pull(handlerInputsIn) // ignore
        }
    }

    /**
     * Both side have sent their close frames, server should close the connection first
     */
    private class WaitingForTransportClose(timeout: Timestamp = closeTimeout) extends InHandler with ProcotolExceptionHandling {
      override def onPush() = {
        grab(handlerInputsIn) match {
          case Tick ⇒
            if (timeout.isPast) completeStage()
            else pull(handlerInputsIn)
          case _ ⇒ pull(handlerInputsIn) // ignore
        }
      }
    }

    /** If upstream has already failed we just wait to be able to deliver our close frame and complete */
    private class SendOutCloseFrameAndComplete(closeFrame: FrameStart) extends InHandler with OutHandler with ProcotolExceptionHandling {
      override def onPush() =
        fail(frameStartsOut, new IllegalStateException("Didn't expect push after completion"))

      override def onPull(): Unit = {
        push(frameStartsOut, closeFrame)
        completeStage()
      }

      override def onUpstreamFinish(): Unit =
        absorbTermination()
    }

    def becomeSendOutCloseFrameAndComplete(frameStart: FrameStart): Unit = {
      val inNOutHandler = new SendOutCloseFrameAndComplete(frameStart)
      setHandler(handlerInputsIn, inNOutHandler)
      setHandler(frameStartsOut, inNOutHandler)
    }

    /** We handle [[ProtocolException]] in a special way (by terminating with a ProtocolError) */
    private trait ProcotolExceptionHandling extends InHandler {
      override def onUpstreamFailure(cause: Throwable): Unit =
        cause match {
          case p: ProtocolException ⇒
            becomeSendOutCloseFrameAndComplete(FrameEvent.closeFrame(Protocol.CloseCodes.ProtocolError))
            absorbTermination()
          case _ ⇒ super.onUpstreamFailure(cause)
        }
    }

    // init handlers

    setHandler(handlerInputsIn, Idle)
    setHandler(frameStartsOut, new OutHandler {
      override def onPull(): Unit = pull(handlerInputsIn)
    })

  }
}

private[http] object FrameOutHandler {
  type Input = AnyRef

  def create(serverSide: Boolean, closeTimeout: FiniteDuration, log: LoggingAdapter): Flow[Input, FrameStart, NotUsed] =
    Flow[Input].via(new FrameOutHandler(serverSide, closeTimeout, log)).named("frameOutHandler")
}