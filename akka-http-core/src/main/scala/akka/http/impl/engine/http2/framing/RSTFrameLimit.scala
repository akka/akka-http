/*
 * Copyright (C) 2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2.framing

import akka.annotation.InternalApi
import akka.http.impl.engine.http2.{ FrameEvent, Http2Compliance }
import akka.http.impl.engine.http2.FrameEvent.RstStreamFrame
import akka.http.impl.engine.http2.Http2Protocol.ErrorCode
import akka.http.scaladsl.settings.Http2ServerSettings
import akka.stream.{ Attributes, FlowShape, Inlet, Outlet }
import akka.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler }

/**
 * INTERNAL API
 */
@InternalApi
private[akka] final class RSTFrameLimit(http2ServerSettings: Http2ServerSettings) extends GraphStage[FlowShape[FrameEvent, FrameEvent]] {

  private val maxResets = http2ServerSettings.maxResets
  private val maxResetsIntervalNanos = http2ServerSettings.maxResetsInterval.toNanos

  val in = Inlet[FrameEvent]("in")
  val out = Outlet[FrameEvent]("out")
  val shape = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) with InHandler with OutHandler {
    private var rstSeen = false
    private var rstCount = 0
    private var rstSpanStartNanos = 0L

    setHandlers(in, out, this)

    override def onPush(): Unit = {
      grab(in) match {
        case frame: RstStreamFrame =>
          rstCount += 1
          val now = System.nanoTime()
          if (!rstSeen) {
            rstSeen = true
            rstSpanStartNanos = now
            push(out, frame)
          } else if ((now - rstSpanStartNanos) <= maxResetsIntervalNanos) {
            if (rstCount > maxResets) {
              failStage(new Http2Compliance.Http2ProtocolException(
                ErrorCode.ENHANCE_YOUR_CALM,
                s"Too many RST frames per second for this connection. (Configured limit ${maxResets}/${http2ServerSettings.maxResetsInterval.toCoarsest})"))
            } else {
              push(out, frame)
            }
          } else {
            // outside time window, reset counter
            rstCount = 1
            rstSpanStartNanos = now
            push(out, frame)
          }

        case frame =>
          push(out, frame)
      }
    }

    override def onPull(): Unit = pull(in)
  }
}
