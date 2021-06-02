/*
 * Copyright (C) 2009-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2.hpack

import java.io.ByteArrayOutputStream
import akka.annotation.InternalApi
import akka.http.impl.engine.http2.Http2Protocol.SettingIdentifier
import akka.http.impl.engine.http2._
import akka.stream.{ Attributes, FlowShape, Inlet, Outlet }
import akka.stream.stage.{ GraphStage, GraphStageLogic, OutHandler, StageLogging }
import akka.util.{ ByteString, Unsafe }

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

  def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new HandleOrPassOnStage[FrameEvent, FrameEvent](shape) with StageLogging {
    val currentMaxFrameSize = Http2Protocol.InitialMaxFrameSize

    val encoder = new akka.http.shaded.com.twitter.hpack.Encoder(Http2Protocol.InitialMaxHeaderTableSize)
    val os = new ByteArrayOutputStream()

    become(Idle)

    object Idle extends State {
      val handleEvent: PartialFunction[FrameEvent, Unit] = {
        case ack @ SettingsAckFrame(s) =>
          applySettings(s)
          push(eventsOut, ack)

        case ParsedHeadersFrame(streamId, endStream, kvs, prioInfo) =>
          kvs.foreach {
            case (key, value) =>
              val keyBytes = new Array[Byte](key.length)
              Unsafe.copyUSAsciiStrToBytes(key, keyBytes)
              val valueStr = value.asInstanceOf[String]
              val valueBytes = new Array[Byte](valueStr.length)
              Unsafe.copyUSAsciiStrToBytes(valueStr, valueBytes)
              encoder.encodeHeader(os, keyBytes, valueBytes, false)
          }
          val result = ByteString(os.toByteArray)
          os.reset()
          if (result.size <= currentMaxFrameSize) push(eventsOut, HeadersFrame(streamId, endStream, endHeaders = true, result, prioInfo))
          else {
            val first = HeadersFrame(streamId, endStream, endHeaders = false, result.take(currentMaxFrameSize), prioInfo)

            emit(eventsOut, first)
            setHandler(eventsOut, new OutHandler {
              var remainingData = result.drop(currentMaxFrameSize)

              def onPull(): Unit = {
                val thisFragment = remainingData.take(currentMaxFrameSize)
                val rest = remainingData.drop(currentMaxFrameSize)
                val last = rest.isEmpty

                push(eventsOut, ContinuationFrame(streamId, endHeaders = last, thisFragment))
                if (last) become(Idle)
                else remainingData = rest
              }
            })
          }
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
}
