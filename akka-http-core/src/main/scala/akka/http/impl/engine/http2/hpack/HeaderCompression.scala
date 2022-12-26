/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2.hpack

import java.io.ByteArrayOutputStream
import akka.annotation.InternalApi
import akka.http.impl.engine.http2.Http2Protocol.SettingIdentifier
import akka.http.impl.engine.http2._
import akka.stream.{ Attributes, FlowShape, Inlet, Outlet }
import akka.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler, StageLogging }
import akka.util.ByteString

import scala.collection.immutable
import FrameEvent._

/**
 * INTERNAL API
 */
@InternalApi
private[http2] object HeaderCompression extends GraphStage[FlowShape[FrameEvent, FrameEvent]] {
  val eventsIn = Inlet[FrameEvent]("HeaderCompression.eventsIn")
  val eventsOut = Outlet[FrameEvent]("HeaderCompression.eventsOut")

  val shape = FlowShape(eventsIn, eventsOut)

  def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) with StageLogging with InHandler with OutHandler { logic =>
    setHandlers(eventsIn, eventsOut, this)
    private val currentMaxFrameSize = Http2Protocol.InitialMaxFrameSize

    val encoder = new akka.http.shaded.com.twitter.hpack.Encoder(Http2Protocol.InitialMaxHeaderTableSize)
    val os = new ByteArrayOutputStream(128)

    def onPull(): Unit = pull(eventsIn)
    def onPush(): Unit = grab(eventsIn) match {
      case ack @ SettingsAckFrame(s) =>
        applySettings(s)
        push(eventsOut, ack)
      case ParsedHeadersFrame(streamId, endStream, kvs, prioInfo) =>
        // When ending the stream without any payload, use a DATA frame rather than
        // a HEADERS frame to work around https://github.com/golang/go/issues/47851.
        if (endStream && kvs.isEmpty) push(eventsOut, DataFrame(streamId, endStream, ByteString.empty))
        else {
          kvs.foreach {
            case (key, value: String) =>
              encoder.encodeHeader(os, key, value, false)
            case (key, value) =>
              throw new IllegalStateException(s"Didn't expect key-value-pair [$key] -> [$value](${value.getClass}) here.")
          }
          val result = ByteString.fromArrayUnsafe(os.toByteArray) // BAOS.toByteArray always creates a copy
          os.reset()
          if (result.size <= currentMaxFrameSize) push(eventsOut, HeadersFrame(streamId, endStream, endHeaders = true, result, prioInfo))
          else {
            val first = HeadersFrame(streamId, endStream, endHeaders = false, result.take(currentMaxFrameSize), prioInfo)

            push(eventsOut, first)
            setHandler(eventsOut, new OutHandler {
              private var remainingData = result.drop(currentMaxFrameSize)

              def onPull(): Unit = {
                val thisFragment = remainingData.take(currentMaxFrameSize)
                val rest = remainingData.drop(currentMaxFrameSize)
                val last = rest.isEmpty

                push(eventsOut, ContinuationFrame(streamId, endHeaders = last, thisFragment))
                if (last) setHandler(eventsOut, logic)
                else remainingData = rest
              }
            })
          }
        }
      case x => push(eventsOut, x)
    }

    def applySettings(s: immutable.Seq[Setting]): Unit =
      s foreach {
        case Setting(SettingIdentifier.SETTINGS_HEADER_TABLE_SIZE, size) =>
          log.debug("Applied SETTINGS_HEADER_TABLE_SIZE({}) in header compression", size)
          // 'size' is strictly spoken unsigned, but the encoder is allowed to
          // pick any size equal to or less than this value (6.5.2)
          if (size >= 0) encoder.setMaxHeaderTableSize(os, size)
          else encoder.setMaxHeaderTableSize(os, Int.MaxValue)
        case _ => // ignore, not applicable to this stage
      }
  }
}
