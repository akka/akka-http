/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2.framing

import akka.event.Logging
import akka.http.impl.engine.http2.{ DataFrame, FrameEvent, Http2Compliance }
import akka.stream.stage.{ InHandler, OutHandler }
import akka.util.ByteString

import scala.collection.immutable

/** INTERNAL API */
private[http] class HttpFrameRendering(stageAccess: LinearGraphStageLogicAccess[FrameEvent, ByteString, RenderingSettingsAccess])
  extends InHandler with OutHandler {
  import stageAccess._

  override def onPush(): Unit = {
    val frame = grab()

    frame match {
      case d @ DataFrame(_, _, payload) if payload.size > settings.maxOutFrameSize ⇒
        // payload too large, we must split it into multiple frames:
        val splitDataDrames = splitByPayloadSize(d, settings.maxOutFrameSize).iterator.map(FrameRenderer.render)
        emitMultiple(splitDataDrames, () ⇒ ()) // TODO would manual handling be more efficient? 

      case _ ⇒
        // normal frame, just render it:
        val rendered = FrameRenderer.render(frame)
        Http2Compliance.requireFrameSizeLessOrEqualThan(rendered.length, settings.maxOutFrameSize, hint = s"Frame type was ${Logging.simpleName(frame)}")
        push(rendered)
    }
  }

  override def onPull(): Unit = pull()

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
