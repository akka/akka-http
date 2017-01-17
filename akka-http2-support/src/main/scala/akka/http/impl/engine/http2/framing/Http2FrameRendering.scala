/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2.framing

import akka.event.Logging
import akka.http.impl.engine.http2.Http2Protocol.FrameType.PUSH_PROMISE
import akka.http.impl.engine.http2.Http2Protocol.SettingIdentifier
import akka.http.impl.engine.http2._
import akka.stream.{ Attributes, FlowShape, Inlet, Outlet }
import akka.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler }
import akka.util.ByteString

import scala.collection.immutable

/** INTERNAL API */
private[http] class Http2FrameRendering extends GraphStage[FlowShape[FrameEvent, ByteString]] {

  val frameIn = Inlet[FrameEvent]("Http2FrameRendering.frameIn")
  val netOut = Outlet[ByteString]("Http2FrameRendering.netOut")

  override def initialAttributes = Attributes.name(Logging.simpleName(getClass))

  override val shape = FlowShape[FrameEvent, ByteString](frameIn, netOut)

  override def createLogic(inheritedAttributes: Attributes) = new GraphStageLogic(shape) with InHandler with OutHandler {
    setHandlers(frameIn, netOut, this)

    // TODO remove
    object settings {
      private var _outMaxFrameSize: Int = 16384 // default
      private var _pushPromiseEnabled: Boolean = true // default (spec 6.6)

      def pushPromiseEnabled: Boolean = _pushPromiseEnabled
      final def pushPromiseDisabled: Boolean = !pushPromiseEnabled
      def maxOutFrameSize: Int = _outMaxFrameSize

      def updatePushPromiseEnabled(value: Boolean) =
        _pushPromiseEnabled = value

      def updateMaxOutFrameSize(value: Int): Unit = {
        Http2Compliance.validateMaxFrameSize(value)
        _outMaxFrameSize = value
      }
    }

    override def onPush(): Unit = {
      val frame = grab(frameIn)

      frame match {
        case d @ DataFrame(_, _, payload) if payload.size > settings.maxOutFrameSize ⇒
          // payload too large, we must split it into multiple frames:
          val splitDataDrames = splitByPayloadSize(d, settings.maxOutFrameSize).iterator.map(FrameRenderer.render)
          emitMultiple(netOut, splitDataDrames) // TODO would manual handling be more efficient? 

        case p: PushPromiseFrame if settings.pushPromiseDisabled ⇒
          failStage(new Http2Compliance.IllegalPushPromiseAttemptException)

        case _ ⇒
          // normal frame, just render it:
          val rendered = FrameRenderer.render(frame)
          Http2Compliance.requireFrameSizeLessOrEqualThan(rendered.length, settings.maxOutFrameSize, hint = s"Frame type was ${Logging.simpleName(frame)}")
          push(netOut, rendered)
      }
    }

    override def onPull(): Unit = pull(frameIn)

    private def splitByPayloadSize(d: DataFrame, size: Int): immutable.Seq[DataFrame] = {
      println(s"Splitting up too large data-frame into smaller frames due to exceeding max frame size. Length: ${d.payload.length}, max: ${size}")
      val parts = d.payload.grouped(size) // TODO optimise, splitAt would be better

      if (d.endStream) {
        // only the last of the split-up frames must close the stream
        val all = parts.map(p ⇒ d.copy(endStream = false, payload = p)).toVector
        // TODO not optimal impl, optimise later:
        all.dropRight(1) ++ Vector(all.last.copy(endStream = true))
      } else {
        parts.map(p ⇒ d.copy(payload = p)).toVector
      }
    }
  }

}
