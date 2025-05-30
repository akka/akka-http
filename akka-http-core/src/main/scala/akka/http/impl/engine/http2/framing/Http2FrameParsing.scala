/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2
package framing

import scala.collection.immutable
import akka.event.LoggingAdapter
import akka.stream.Attributes
import akka.stream.impl.io.ByteStringParser
import akka.stream.stage.GraphStageLogic
import akka.util.OptionVal
import Http2Protocol.{ ErrorCode, Flags, FrameType, SettingIdentifier }
import akka.annotation.InternalApi
import FrameEvent._
import akka.http.impl.engine.http2.Http2Compliance.Http2ProtocolException
import akka.stream.impl.io.ByteStringParser.ByteReader

import scala.annotation.tailrec

/** INTERNAL API */
@InternalApi
private[http] object Http2FrameParsing {

  def readSettings(payload: ByteStringParser.ByteReader, log: LoggingAdapter): immutable.Seq[Setting] = {
    @tailrec def readSettings(read: List[Setting]): immutable.Seq[Setting] =
      if (payload.hasRemaining) {
        val id = payload.readShortBE()
        val value = payload.readIntBE()
        val read0 = SettingIdentifier.byId(id) match {
          case OptionVal.Some(s) =>
            Setting(s, value) :: read
          case _ =>
            log.debug("Ignoring unknown setting identifier {}", id)
            read
        }
        readSettings(read0)
      } else read.reverse

    readSettings(Nil)
  }

  def parseFrame(tpe: FrameType, flags: ByteFlag, streamId: Int, payload: ByteReader, log: LoggingAdapter): FrameEvent = {
    // TODO: add @switch? seems non-trivial for now
    tpe match {
      case FrameType.GOAWAY =>
        Http2Compliance.requireZeroStreamId(streamId)
        GoAwayFrame(payload.readIntBE(), ErrorCode.byId(payload.readIntBE()), payload.takeAll())

      case FrameType.HEADERS =>
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

      case FrameType.DATA =>
        val pad = Flags.PADDED.isSet(flags)
        val endStream = Flags.END_STREAM.isSet(flags)

        val paddingLength =
          if (pad) payload.readByte() & 0xff
          else 0

        DataFrame(streamId, endStream, payload.take(payload.remainingSize - paddingLength))

      case FrameType.SETTINGS =>
        val ack = Flags.ACK.isSet(flags)
        Http2Compliance.requireZeroStreamId(streamId)

        if (ack) {
          // validate that payload is empty: (6.5)
          if (payload.hasRemaining)
            throw new Http2Compliance.IllegalPayloadInSettingsAckFrame(payload.remainingSize, s"SETTINGS ACK frame MUST NOT contain payload (spec 6.5)!")

          SettingsAckFrame(Nil) // TODO if we were to send out settings, here would be the spot to include the acks for the ones we've sent out
        } else {

          if (payload.remainingSize % 6 != 0) throw new Http2Compliance.IllegalPayloadLengthInSettingsFrame(payload.remainingSize, "SETTINGS payload MUST be a multiple of multiple of 6 octets")
          SettingsFrame(readSettings(payload, log))
        }

      case FrameType.WINDOW_UPDATE =>
        // TODO: check frame size
        // TODO: check flags
        val increment = payload.readIntBE()
        Http2Compliance.requirePositiveWindowUpdateIncrement(streamId, increment)

        WindowUpdateFrame(streamId, increment)

      case FrameType.CONTINUATION =>
        val endHeaders = Flags.END_HEADERS.isSet(flags)

        ContinuationFrame(streamId, endHeaders, payload.remainingData)

      case FrameType.PING =>
        // see 6.7
        Http2Compliance.requireFrameSize(payload.remainingSize, 8)
        Http2Compliance.requireZeroStreamId(streamId)
        val ack = Flags.ACK.isSet(flags)
        PingFrame(ack, payload.remainingData)

      case FrameType.RST_STREAM =>
        Http2Compliance.requireFrameSize(payload.remainingSize, 4)
        Http2Compliance.requireNonZeroStreamId(streamId)
        RstStreamFrame(streamId, ErrorCode.byId(payload.readIntBE()))

      case FrameType.PRIORITY =>
        Http2Compliance.requireFrameSize(payload.remainingSize, 5)
        val streamDependency = payload.readIntBE() // whole word
        val exclusiveFlag = (streamDependency >>> 31) == 1 // most significant bit for exclusive flag
        val dependencyId = streamDependency & 0x7fffffff // remaining 31 bits for the dependency part
        val priority = payload.readByte() & 0xff
        Http2Compliance.requireNoSelfDependency(streamId, dependencyId)
        PriorityFrame(streamId, exclusiveFlag, dependencyId, priority)

      case FrameType.PUSH_PROMISE =>
        val pad = Flags.PADDED.isSet(flags)
        val endHeaders = Flags.END_HEADERS.isSet(flags)

        val paddingLength =
          if (pad) payload.readByte() & 0xff
          else 0

        val promisedStreamId = payload.readIntBE()

        PushPromiseFrame(streamId, endHeaders, promisedStreamId, payload.take(payload.remainingSize - paddingLength))

      case tpe => // TODO: remove once all stream types are defined
        UnknownFrameEvent(tpe, flags, streamId, payload.remainingData)
    }
  }
}

/** INTERNAL API */
@InternalApi
private[http2] class Http2FrameParsing(shouldReadPreface: Boolean, log: LoggingAdapter) extends ByteStringParser[FrameEvent] {
  import ByteStringParser._
  import Http2FrameParsing._

  abstract class Step extends ParseStep[FrameEvent]

  def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new ParsingLogic {
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
            throw new Http2ProtocolException("Expected ConnectionPreface!")
      }

      object ReadFrame extends Step {
        override def parse(reader: ByteReader): ParseResult[FrameEvent] = {
          val length = reader.readShortBE() << 8 | reader.readByte()
          val tpe = reader.readByte()
          val flags = new ByteFlag(reader.readByte())
          val streamId = reader.readIntBE()
          // TODO: assert that reserved bit is 0 by checking if streamId > 0
          val payload = reader.take(length)
          val maybeframe = FrameType.byId(tpe) match {
            case OptionVal.Some(ft) =>
              Some(parseFrame(ft, flags, streamId, new ByteReader(payload), log))
            case _ =>
              log.debug("Ignoring unknown frame type {}", tpe)
              None
          }
          ParseResult(maybeframe, ReadFrame, acceptUpstreamFinish = true)
        }
      }

    }
}

