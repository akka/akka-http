/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2
package framing

import akka.annotation.InternalApi
import akka.http.impl.engine.http2.FrameEvent._
import akka.http.impl.engine.http2.Http2Protocol.FrameType
import akka.util.ByteString

import java.nio.ByteOrder
import scala.annotation.tailrec

/** INTERNAL API */
@InternalApi
private[http2] object FrameRenderer {
  implicit val byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN

  def render(frame: FrameEvent): ByteString =
    frame match {
      case GoAwayFrame(lastStreamId, errorCode, debug) =>
        Frame(
          8 + debug.length,
          Http2Protocol.FrameType.GOAWAY,
          Http2Protocol.Flags.NO_FLAGS,
          Http2Protocol.NoStreamId
        )
          .putInt32(lastStreamId)
          .putInt32(errorCode.id)
          // appends debug data, if any
          .put(debug)
          .build()

      case DataFrame(streamId, endStream, payload) =>
        // TODO: should padding be emitted? In which cases?
        Frame(
          payload.length,
          Http2Protocol.FrameType.DATA,
          Http2Protocol.Flags.END_STREAM.ifSet(endStream),
          streamId
        )
          .put(payload)
          .build()
      case HeadersFrame(streamId, endStream, endHeaders, headerBlockFragment, prioInfo) =>
        Frame(
          (if (prioInfo.isDefined) 5 else 0) + headerBlockFragment.length,
          Http2Protocol.FrameType.HEADERS,
          Http2Protocol.Flags.END_STREAM.ifSet(endStream) |
            Http2Protocol.Flags.END_HEADERS.ifSet(endHeaders) |
            Http2Protocol.Flags.PRIORITY.ifSet(prioInfo.isDefined),
          streamId
        )
          .putPriorityInfo(prioInfo)
          .put(headerBlockFragment)
          .build()

      case WindowUpdateFrame(streamId, windowSizeIncrement) =>
        Frame(
          4,
          Http2Protocol.FrameType.WINDOW_UPDATE,
          Http2Protocol.Flags.NO_FLAGS,
          streamId
        )
          .putInt32(windowSizeIncrement)
          .build()

      case ContinuationFrame(streamId, endHeaders, payload) =>
        Frame(
          payload.length,
          Http2Protocol.FrameType.CONTINUATION,
          Http2Protocol.Flags.END_HEADERS.ifSet(endHeaders),
          streamId
        )
          .put(payload)
          .build()

      case SettingsFrame(settings) =>
        val b = Frame(
          settings.length * 6,
          Http2Protocol.FrameType.SETTINGS,
          Http2Protocol.Flags.NO_FLAGS,
          Http2Protocol.NoStreamId
        )

        @tailrec def renderNext(remaining: Seq[Setting]): Unit =
          remaining match {
            case Setting(id, value) +: remaining =>
              b.putInt16(id.id)
              b.putInt32(value)

              renderNext(remaining)
            case Nil   =>
            case other => throw new IllegalArgumentException(s"Unexpected remaining: $other") // compiler completeness check pleaser
          }

        renderNext(settings)
        b.build()

      case _: SettingsAckFrame =>
        Frame(
          0,
          Http2Protocol.FrameType.SETTINGS,
          Http2Protocol.Flags.ACK,
          Http2Protocol.NoStreamId
        )
          .build()

      case PingFrame(ack, data) =>
        Frame(
          data.length,
          Http2Protocol.FrameType.PING,
          Http2Protocol.Flags.ACK.ifSet(ack),
          Http2Protocol.NoStreamId
        )
          .put(data)
          .build()

      case RstStreamFrame(streamId, errorCode) =>
        Frame(
          4,
          Http2Protocol.FrameType.RST_STREAM,
          Http2Protocol.Flags.NO_FLAGS,
          streamId
        )
          .putInt32(errorCode.id)
          .build()

      case PushPromiseFrame(streamId, endHeaders, promisedStreamId, headerBlockFragment) =>
        Frame(
          4 + headerBlockFragment.length,
          Http2Protocol.FrameType.PUSH_PROMISE,
          Http2Protocol.Flags.END_HEADERS.ifSet(endHeaders),
          streamId
        )
          .putInt32(promisedStreamId)
          .put(headerBlockFragment)
          .build()

      case frame @ PriorityFrame(streamId, _, _, _) =>
        Frame(
          5,
          Http2Protocol.FrameType.PRIORITY,
          Http2Protocol.Flags.NO_FLAGS,
          streamId
        )
          .putPriorityInfo(frame)
          .build()
      case _ => throw new IllegalStateException(s"Unexpected frame type ${frame.frameTypeName}.")
    }

  def renderFrame(tpe: FrameType, flags: ByteFlag, streamId: Int, payload: ByteString): ByteString =
    Frame(payload.length, tpe, flags, streamId)
      .put(payload)
      .build()

  private object Frame {
    def apply(payloadSize: Int, tpe: FrameType, flags: ByteFlag, streamId: Int): Frame = new Frame(payloadSize, tpe, flags, streamId)
  }
  private class Frame(payloadSize: Int, tpe: FrameType, flags: ByteFlag, streamId: Int) {
    private val targetSize = 9 + payloadSize
    private val buffer = new Array[Byte](targetSize)
    private var pos = 0

    putInt24(payloadSize)
    putByte(tpe.id.toByte)
    putByte(flags.value.toByte)
    putInt32(streamId)

    def putPriorityInfo(priorityFrame: PriorityFrame): Frame = {
      val exclusiveBit: Int = if (priorityFrame.exclusiveFlag) 0x80000000 else 0
      putInt32(exclusiveBit | priorityFrame.streamDependency)
      putByte(priorityFrame.weight.toByte)
    }
    def putPriorityInfo(priorityFrame: Option[PriorityFrame]): Frame =
      priorityFrame match {
        case Some(p) => putPriorityInfo(p)
        case None    => this
      }

    def putByte(byte: Byte): this.type = {
      buffer(pos) = byte
      pos += 1
      this
    }
    def putInt32(value: Int): this.type = {
      buffer(pos + 0) = (value >> 24).toByte
      buffer(pos + 1) = (value >> 16).toByte
      buffer(pos + 2) = (value >> 8).toByte
      buffer(pos + 3) = (value >> 0).toByte
      pos += 4
      this
    }
    def putInt24(value: Int): this.type = {
      buffer(pos + 0) = (value >> 16).toByte
      buffer(pos + 1) = (value >> 8).toByte
      buffer(pos + 2) = (value >> 0).toByte
      pos += 3
      this
    }
    def putInt16(value: Int): this.type = {
      buffer(pos + 0) = (value >> 8).toByte
      buffer(pos + 1) = (value >> 0).toByte
      pos += 2
      this
    }
    def put(bytes: ByteString): this.type =
      if (bytes.isEmpty) this
      else {
        bytes.copyToArray(buffer, pos)
        pos += bytes.length
        this
      }

    def build(): ByteString =
      if (pos != targetSize) throw new IllegalStateException(s"Did not write exactly $targetSize bytes but $pos")
      else ByteString.fromArrayUnsafe(buffer)
  }
}
