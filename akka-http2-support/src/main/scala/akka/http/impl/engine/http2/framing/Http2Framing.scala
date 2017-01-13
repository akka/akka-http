/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2.framing

import akka.http.impl.engine.http2.{ FrameEvent, Http2Compliance }
import akka.stream._
import akka.stream.stage._
import akka.util.ByteString

/**
 * Handles all parsing and rendering of frames.
 * MAY independently from downstream stages write a SettingAck back if a Setting frame was about changing the parser's state.
 *
 * @param shouldReadPreface frameParser setting
 */
//@InternalAPI
private[http] final class Http2Framing(shouldReadPreface: Boolean)
  extends GraphStage[BidiShape[FrameEvent, ByteString, ByteString, FrameEvent]] { stage ⇒

  val frameIn = Inlet[FrameEvent]("Http2Framing.frameIn")
  val netOut = Outlet[ByteString]("Http2Framing.netOut")

  val frameOut = Outlet[FrameEvent]("Http2Framing.frameOut")
  val netIn = Inlet[ByteString]("Http2Framing.netIn")

  override val shape: BidiShape[FrameEvent, ByteString, ByteString, FrameEvent] =
    BidiShape[FrameEvent, ByteString, ByteString, FrameEvent](frameIn, netOut, netIn, frameOut)

  override def createLogic(inheritedAttributes: Attributes) = new GraphStageLogic(shape) { logic ⇒

    // active settings -------------------------------------------------------------------------------------------------

    // mutations of settings may only be done from within the rendering / parsing parts of this stage
    object settings extends FramingSettings {
      private var _outMaxFrameSize: Int = 16384 // default
      override def shouldReadPreface: Boolean = stage.shouldReadPreface

      override def maxOutFrameSize: Int = _outMaxFrameSize

      override def updateMaxOutFrameSize(value: Int): Unit = {
        Http2Compliance.validateMaxFrameSize(value)
        _outMaxFrameSize = value
      }
    }

    // frames -> network ----------------------------------------------------------------------------------------------- 
    setHandlers(frameIn, netOut, new HttpFrameRendering(new LinearGraphStageLogicAccess[FrameEvent, ByteString] {
      override def settings = logic.settings

      override def push(el: ByteString): Unit = logic.push(netOut, el)
      override def emitMultiple(bytes: Iterator[ByteString], andThen: () ⇒ Unit): Unit = logic.emitMultiple(netOut, bytes, andThen)
      override def pull(): Unit = logic.pull(frameIn)
      override def grab(): FrameEvent = logic.grab(frameIn)

      override def completeStage(): Unit = logic.completeStage()
      override def completeOut(): Unit = logic.complete(netOut)
      override def failOut(ex: Throwable): Unit = logic.fail(netOut, ex)
      override def cancel(): Unit = logic.cancel(frameIn)

      override def isInClosed(): Boolean = logic.isClosed(frameIn)
      override def isInAvailable(): Boolean = logic.isAvailable(frameIn)
      override def isOutClosed(): Boolean = logic.isClosed(netOut)
      override def isOutAvailable(): Boolean = logic.isAvailable(netOut)
    }))

    setHandlers(netIn, frameOut, new HttpFrameParsing(shouldReadPreface, new LinearGraphStageLogicAccess[ByteString, FrameEvent] {
      override def settings = logic.settings

      override def push(frame: FrameEvent): Unit = logic.push(frameOut, frame)
      override def emitMultiple(frames: Iterator[FrameEvent], andThen: () ⇒ Unit): Unit = logic.emitMultiple(frameOut, frames, andThen)
      override def pull(): Unit = logic.pull(netIn)
      override def grab(): ByteString = logic.grab(netIn)

      override def completeStage(): Unit = logic.completeStage()
      override def completeOut(): Unit = logic.complete(frameOut)
      override def failOut(ex: Throwable): Unit = logic.fail(frameOut, ex)
      override def cancel(): Unit = logic.cancel(netIn)

      override def isInClosed(): Boolean = logic.isClosed(netIn)
      override def isInAvailable(): Boolean = logic.isAvailable(netIn)
      override def isOutClosed(): Boolean = logic.isClosed(frameOut)
      override def isOutAvailable(): Boolean = logic.isAvailable(frameOut)
    }))
  }
}

trait FramingSettings {
  def shouldReadPreface: Boolean

  def maxOutFrameSize: Int
  def updateMaxOutFrameSize(l: Int): Unit
}

/**
 * INTERNAL API
 * Used to communicate with GraphStageLogic from outer class, since we otherwise can't do this since all these
 * methods are protected in GraphStageLogic
 */
private[http] trait LinearGraphStageLogicAccess[In, Out] {
  def settings: FramingSettings

  def push(el: Out)
  def emitMultiple(elems: Iterator[Out], andThen: () ⇒ Unit): Unit
  def pull(): Unit
  def grab(): In

  def completeStage()
  def completeOut()
  def failOut(ex: Throwable)
  def cancel()

  def isInClosed(): Boolean
  def isInAvailable(): Boolean
  def isOutClosed(): Boolean
  def isOutAvailable(): Boolean
}

