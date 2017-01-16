/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2.framing

import java.util

import akka.NotUsed
import akka.http.impl.engine.http2._
import akka.http.impl.engine.http2.Http2Protocol.{ ErrorCode, Flags, FrameType, SettingIdentifier }
import akka.http.impl.engine.http2.Http2Protocol.FrameType._
import akka.http.impl.util.LogByteStringTools
import akka.stream._
import akka.stream.stage._
import akka.util.ByteString

import scala.collection.immutable
import scala.collection.immutable.Iterable

/** INTERNAL API */
private[http] class HttpFrameParsing(shouldReadPreface: Boolean, stageAccess: LinearGraphStageLogicAccess[ByteString, FrameEvent, ParsingSettingsAccess])
  extends HttpByteStringParser(stageAccess) {
  import HttpByteStringParser._

  abstract class Step extends ParseStep[FrameEvent]

  startWith {
    if (shouldReadPreface) ReadPreface
    else ReadFrame
  }

  object ReadPreface extends Step {
    override def parse(reader: ByteReader): ParseResult[FrameEvent] =
      if (reader.remainingSize < 24) throw NeedMoreData
      else if (reader.take(24) == Http2Protocol.ClientConnectionPreface)
        ParseResult(None, ReadFrame, acceptUpstreamFinish = false)
      else
        throw new RuntimeException("Expected ConnectionPreface!")
  }

  object ReadFrame extends Step {
    override def parse(reader: ByteReader): ParseResult[FrameEvent] = {
      val length = reader.readShortBE() << 8 | reader.readByte()
      val tpe = reader.readByte() // TODO: make sure it's valid
      val flags = new ByteFlag(reader.readByte())
      val streamId = reader.readIntBE()
      // TODO: assert that reserved bit is 0 by checking if streamId > 0
      val payload = reader.take(length)
      val frame = parseFrame(FrameType.byId(tpe), flags, streamId, new ByteReader(payload))

      ParseResult(Some(frame), ReadFrame, acceptUpstreamFinish = true)
    }
  }

  def parseFrame(tpe: FrameType, flags: ByteFlag, streamId: Int, payload: ByteReader): FrameEvent = {
    // TODO: add @switch? seems non-trivial for now
    tpe match {
      case GOAWAY ⇒
        Http2Compliance.requireZeroStreamId(streamId)
        GoAwayFrame(payload.readIntBE(), ErrorCode.byId(payload.readIntBE()), payload.takeAll())

      case HEADERS ⇒
        val pad = Flags.PADDED.isSet(flags)
        val endStream = Flags.END_STREAM.isSet(flags)
        val endHeaders = Flags.END_HEADERS.isSet(flags)
        val priority = Flags.PRIORITY.isSet(flags)

        val paddingLength =
          if (pad) payload.readByte() & 0xff
          else 0

        val priorityInfo =
          if (priority) {
            val dependencyAndE = payload.readIntBE()
            val weight = payload.readByte() & 0xff

            val exclusiveFlag = (dependencyAndE >>> 31) == 1 // most significant bit for exclusive flag
            val dependencyId = dependencyAndE & 0x7fffffff // remaining 31 bits for the dependency part
            Http2Compliance.requireNoSelfDependency(streamId, dependencyId)
            Some(PriorityFrame(streamId, exclusiveFlag, dependencyId, weight))
          } else
            None

        HeadersFrame(streamId, endStream, endHeaders, payload.take(payload.remainingSize - paddingLength), priorityInfo)

      case DATA ⇒
        val pad = Flags.PADDED.isSet(flags)
        val endStream = Flags.END_STREAM.isSet(flags)

        val paddingLength =
          if (pad) payload.readByte() & 0xff
          else 0

        DataFrame(streamId, endStream, payload.take(payload.remainingSize - paddingLength))

      case SETTINGS ⇒
        val ack = Flags.ACK.isSet(flags)
        Http2Compliance.requireZeroStreamId(streamId)

        if (ack) {
          // validate that payload is empty: (6.5)
          if (payload.hasRemaining)
            throw new Http2Compliance.IllegalPayloadInSettingsAckFrame(payload.remainingSize, s"SETTINGS ACK frame MUST NOT contain payload (spec 6.5)!")

          SettingsAckFrame
        } else {
          def readSettings(read: List[Setting]): immutable.Seq[Setting] =
            if (payload.hasRemaining) {
              val id = payload.readShortBE()
              val value = payload.readIntBE()
              readSettings(Setting(SettingIdentifier.byId(id), value) :: read)
            } else read.reverse

          if (payload.remainingSize % 6 != 0) throw new Http2Compliance.IllegalPayloadLengthInSettingsFrame(payload.remainingSize, "SETTINGS payload MUDT be a multiple of multiple of 6 octets")
          applySetting(SettingsFrame(readSettings(Nil)))
        }

      case WINDOW_UPDATE ⇒
        // TODO: check frame size
        // TODO: check flags
        // TODO: check reserved flag
        // TODO: check that increment is > 0
        val increment = payload.readIntBE()
        WindowUpdateFrame(streamId, increment)

      case CONTINUATION ⇒
        val endHeaders = Flags.END_HEADERS.isSet(flags)

        ContinuationFrame(streamId, endHeaders, payload.remainingData)

      case PING ⇒
        Http2Compliance.requireZeroStreamId(streamId)
        val ack = Flags.ACK.isSet(flags)
        // FIXME: ensure data size is 8
        PingFrame(ack, payload.remainingData)

      case RST_STREAM ⇒
        Http2Compliance.requireFrameSize(payload.remainingSize, 4)
        RstStreamFrame(streamId, ErrorCode.byId(payload.readIntBE()))

      case PRIORITY ⇒
        val streamDependency = payload.readIntBE() // whole word
        val exclusiveFlag = (streamDependency >>> 31) == 1 // most significant bit for exclusive flag
        val dependencyPart = streamDependency & 0x7fffffff // remaining 31 bits for the dependency part
        val priority = payload.readByte() & 0xff
        PriorityFrame(streamId, exclusiveFlag, dependencyPart, priority)

      case tpe ⇒ // TODO: remove once all stream types are defined
        UnknownFrameEvent(tpe, flags, streamId, payload.remainingData)
    }
  }

  // FIXME this should be smarter, we need to somehow manage when we send back the ACK basically...
  private def applySetting(s: SettingsFrame): SettingsFrame = {
    try {
      s.settings.foreach {
        case Setting(SettingIdentifier.SETTINGS_MAX_FRAME_SIZE, value) ⇒
          stageAccess.settings.updateMaxOutFrameSize(value)
          debug(s"Set outgoing SETTINGS_MAX_FRAME_SIZE to [${value}]")

        case Setting(SettingIdentifier.SETTINGS_ENABLE_PUSH, value) ⇒
          val pushFrameAllowed = Http2Compliance.parseSettungsEnablePushValue(value)
          debug(s"Set ENABLE_PUSH to ${pushFrameAllowed}")
          stageAccess.settings.updatePushPromiseEnabled(pushFrameAllowed)

        case setting ⇒
          debug(s"Not applying ${setting} in framing stage directly...") // TODO cleanup once complete handling done
      }
    } catch {
      case ex: Throwable ⇒
        stageAccess.failOut(ex)
    }

    s
  }

  private def debug(s: String) = println(s)
}

/** INTERNAL API */
private[akka] object HttpFrameParsing {

  /** Primarily used for testing parsing */
  def flow(itShouldReadPreface: Boolean) =
    new GraphStage[FlowShape[ByteString, FrameEvent]] { stage ⇒

      val netIn = Inlet[ByteString]("Http2Framing.netIn")
      val frameOut = Outlet[FrameEvent]("Http2Framing.frameOut")

      override def initialAttributes = Attributes.name("HttpFrameParsing")

      override val shape = FlowShape[ByteString, FrameEvent](netIn, frameOut)

      override def createLogic(inheritedAttributes: Attributes) = new GraphStageLogic(shape) { logic ⇒

        // TODO should be able to de duplicate the settings
        object settings extends FramingSettings with ParsingSettingsAccess with RenderingSettingsAccess {
          private var _outMaxFrameSize: Int = 16384 // default
          private var _pushPromiseEnaled = true

          override def shouldReadPreface: Boolean = itShouldReadPreface
          override def maxOutFrameSize: Int = _outMaxFrameSize
          override def pushPromiseEnabled: Boolean = _pushPromiseEnaled

          override def updatePushPromiseEnabled(value: Boolean): Unit =
            _pushPromiseEnaled = value
          override def updateMaxOutFrameSize(value: Int): Unit = {
            Http2Compliance.validateMaxFrameSize(value)
            _outMaxFrameSize = value
          }
        }

        setHandlers(netIn, frameOut, new HttpFrameParsing(settings.shouldReadPreface, new LinearGraphStageLogicAccess[ByteString, FrameEvent, ParsingSettingsAccess] {
          override def settings = logic.settings

          override def push(frame: FrameEvent): Unit = logic.push(frameOut, frame)
          override def emitMultiple(frames: Iterator[FrameEvent], andThen: () ⇒ Unit): Unit = logic.emitMultiple(frameOut, frames, andThen)

          override def pull(): Unit = logic.pull(netIn)
          override def grab(): ByteString = {

            logic.grab(netIn)
          }

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
}
