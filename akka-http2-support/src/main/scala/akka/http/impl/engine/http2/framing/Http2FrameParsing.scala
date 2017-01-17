/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2.framing

import akka.event.Logging
import akka.http.impl.engine.http2.Http2Protocol.FrameType._
import akka.http.impl.engine.http2.Http2Protocol.{ ErrorCode, Flags, FrameType, SettingIdentifier }
import akka.http.impl.engine.http2._
import akka.stream._
import akka.stream.impl.io.ByteStringParser
import akka.stream.stage._

import scala.collection.immutable

/** INTERNAL API */
private[http] class Http2FrameParsing(shouldReadPreface: Boolean) extends ByteStringParser[FrameEvent] { stage ⇒
  import ByteStringParser._

  abstract class Step extends ParseStep[FrameEvent]

  override def initialAttributes = Attributes.name(Logging.simpleName(getClass))

  def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new ParsingLogic {

      private var _outMaxFrameSize: Int = 16384 // default
      private var _pushPromiseEnabled: Boolean = true // default (spec 6.6)

      object settings {

        def shouldReadPreface: Boolean = stage.shouldReadPreface
        def pushPromiseEnabled: Boolean = _pushPromiseEnabled
        def maxOutFrameSize: Int = _outMaxFrameSize

        def updatePushPromiseEnabled(value: Boolean): Unit =
          _pushPromiseEnabled = value

        def updateMaxOutFrameSize(value: Int): Unit = {
          Http2Compliance.validateMaxFrameSize(value)
          _outMaxFrameSize = value
        }
      }

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
        s.settings.foreach {
          case Setting(SettingIdentifier.SETTINGS_MAX_FRAME_SIZE, value) ⇒
            settings.updateMaxOutFrameSize(value)
            debug(s"Set outgoing SETTINGS_MAX_FRAME_SIZE to [${value}]")

          case Setting(SettingIdentifier.SETTINGS_ENABLE_PUSH, value) ⇒
            val pushFrameAllowed = Http2Compliance.parseSettungsEnablePushValue(value)
            debug(s"Set ENABLE_PUSH to ${pushFrameAllowed}")
            settings.updatePushPromiseEnabled(pushFrameAllowed)

          case setting ⇒
            debug(s"Not applying ${setting} in framing stage directly...") // TODO cleanup once complete handling done
        }

        s
      }

    }

  private def debug(s: String) = println(s)

}
