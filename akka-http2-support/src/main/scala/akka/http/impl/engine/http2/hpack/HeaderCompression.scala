/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2.hpack

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

import akka.http.impl.engine.http2.Http2Protocol.SettingIdentifier.SETTINGS_HEADER_TABLE_SIZE
import akka.http.impl.engine.http2._
import akka.stream.{ Attributes, FlowShape, Inlet, Outlet }
import akka.stream.stage.{ GraphStage, GraphStageLogic, OutHandler, StageLogging }
import akka.util.ByteString

/**
 * INTERNAL API
 */
private[http2] object HeaderCompression extends GraphStage[FlowShape[FrameEvent, FrameEvent]] {

  final val InitialMaxHeaderTableSize = 4096 // according to spec 6.5.2

  val eventsIn = Inlet[FrameEvent]("HeaderCompression.eventsIn")
  val eventsOut = Outlet[FrameEvent]("HeaderCompression.eventsOut")

  val shape = FlowShape(eventsIn, eventsOut)

  def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new HandleOrPassOnStage[FrameEvent, FrameEvent](shape) with StageLogging {
    var currentMaxFrameSize = Http2Protocol.InitialMaxFrameSize

    val encoder = new com.twitter.hpack.Encoder(InitialMaxHeaderTableSize)
    val os = new ByteArrayOutputStream()
    become(Idle)

    object Idle extends State {
      override val handleEvent: PartialFunction[FrameEvent, Unit] = {
        case fr @ ParsedHeadersFrame(streamId, endStream, kvs, prioInfo) ⇒
          os.reset()
          kvs.foreach {
            case (key, value) ⇒
              encoder.encodeHeader(os, key.getBytes(StandardCharsets.UTF_8), value.getBytes(StandardCharsets.UTF_8), false)
          }
          val result = ByteString(os.toByteArray)
          if (result.size <= currentMaxFrameSize) {
            push(eventsOut, HeadersFrame(streamId, endStream, endHeaders = true, result, prioInfo))
          } else {
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
        case e: SyntheticFrameEvent ⇒
          e match {
            case SyntheticHpackEncoderSettingFrame(Setting(SETTINGS_HEADER_TABLE_SIZE, value)) ⇒
              log.warning("Set outgoing compression table size to {}", value)
              encoder.setMaxHeaderTableSize(os, value)
              push(eventsOut, SettingsAckFrame)
            case _ ⇒
              throw new Exception("Unexpected synthetic frame event! Was: " + e)
          }
      }
    }
  }
}
