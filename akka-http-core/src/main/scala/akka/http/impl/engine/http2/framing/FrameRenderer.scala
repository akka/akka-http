/*
 * Copyright (C) 2009-2021 Lightbend Inc. <https://www.lightbend.com>
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
        HeaderBuilder(8 + debug.size)
          .putHeader(
            Http2Protocol.FrameType.GOAWAY,
            Http2Protocol.Flags.NO_FLAGS,
            Http2Protocol.NoStreamId,
          )
          .putInt32(lastStreamId)
          .putInt32(errorCode.id)
          // appends debug data, if any
          .put(debug)
          .result()

      case DataFrame(streamId, endStream, payload) =>
        // TODO: should padding be emitted? In which cases?
        HeaderBuilder(payload.size)
          .putHeader(
            Http2Protocol.FrameType.DATA,
            Http2Protocol.Flags.END_STREAM.ifSet(endStream),
            streamId
          )
          .put(payload)
          .result()
      case HeadersFrame(streamId, endStream, endHeaders, headerBlockFragment, prioInfo) =>
        HeaderBuilder(headerBlockFragment.size + (if (prioInfo.isDefined) 5 else 0))
          .putHeader(
            Http2Protocol.FrameType.HEADERS,
            Http2Protocol.Flags.END_STREAM.ifSet(endStream) |
              Http2Protocol.Flags.END_HEADERS.ifSet(endHeaders) |
              Http2Protocol.Flags.PRIORITY.ifSet(prioInfo.isDefined),
            streamId
          )
          .putPriorityInfo(prioInfo)
          .put(headerBlockFragment)
          .result()

      case WindowUpdateFrame(streamId, windowSizeIncrement) =>
        HeaderBuilder(4)
          .putHeader(
            Http2Protocol.FrameType.WINDOW_UPDATE,
            Http2Protocol.Flags.NO_FLAGS,
            streamId
          )
          .putInt32(windowSizeIncrement)
          .result()

      case ContinuationFrame(streamId, endHeaders, payload) =>
        HeaderBuilder(payload.size)
          .putHeader(
            Http2Protocol.FrameType.CONTINUATION,
            Http2Protocol.Flags.END_HEADERS.ifSet(endHeaders),
            streamId
          )
          .put(payload)
          .result()

      case SettingsFrame(settings) =>
        val b = HeaderBuilder(settings.size * 6)
        @tailrec def renderNext(remaining: Seq[Setting]): Unit =
          remaining match {
            case Setting(id, value) +: remaining =>
              b.putInt16(id.id)
              b.putInt32(value)

              renderNext(remaining)
            case Nil =>
          }

        b.putHeader(
          Http2Protocol.FrameType.SETTINGS,
          Http2Protocol.Flags.NO_FLAGS,
          Http2Protocol.NoStreamId
        )
        renderNext(settings)
        b.result()

      case _: SettingsAckFrame =>
        HeaderBuilder(0)
          .putHeader(
            Http2Protocol.FrameType.SETTINGS,
            Http2Protocol.Flags.ACK,
            Http2Protocol.NoStreamId
          )
          .result()

      case PingFrame(ack, data) =>
        HeaderBuilder(data.size)
          .putHeader(
            Http2Protocol.FrameType.PING,
            Http2Protocol.Flags.ACK.ifSet(ack),
            Http2Protocol.NoStreamId
          )
          .put(data)
          .result()

      case RstStreamFrame(streamId, errorCode) =>
        HeaderBuilder(4)
          .putHeader(
            Http2Protocol.FrameType.RST_STREAM,
            Http2Protocol.Flags.NO_FLAGS,
            streamId
          )
          .putInt32(errorCode.id)
          .result()

      case PushPromiseFrame(streamId, endHeaders, promisedStreamId, headerBlockFragment) =>
        HeaderBuilder(4 + headerBlockFragment.size)
          .putHeader(
            Http2Protocol.FrameType.PUSH_PROMISE,
            Http2Protocol.Flags.END_HEADERS.ifSet(endHeaders),
            streamId
          )
          .putInt32(promisedStreamId)
          .put(headerBlockFragment)
          .result()

      case frame @ PriorityFrame(streamId, _, _, _) =>
        HeaderBuilder(5)
          .putHeader(
            Http2Protocol.FrameType.PRIORITY,
            Http2Protocol.Flags.NO_FLAGS,
            streamId
          )
          .putPriorityInfo(frame)
          .result()
      case _ => throw new IllegalStateException(s"Unexpected frame type ${frame.frameTypeName}.")
    }

  private class HeaderBuilder(payloadSize: Int) {
    private val targetSize = 9 + payloadSize
    private val buffer = new Array[Byte](targetSize)
    private var pos = 0

    def putHeader(tpe: FrameType, flags: ByteFlag, streamId: Int): HeaderBuilder = {
      putInt24(payloadSize)
      putByte(tpe.id.toByte)
      putByte(flags.value.toByte)
      putInt32(streamId)
    }
    def putPriorityInfo(priorityFrame: PriorityFrame): HeaderBuilder = {
      val exclusiveBit: Int = if (priorityFrame.exclusiveFlag) 0x80000000 else 0
      putInt32(exclusiveBit | priorityFrame.streamDependency)
      putByte(priorityFrame.weight.toByte)
    }
    def putPriorityInfo(priorityFrame: Option[PriorityFrame]): HeaderBuilder =
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
        pos += bytes.size
        this
      }

    def result(): ByteString =
      if (pos != targetSize) throw new IllegalStateException(s"Did not write exactly $targetSize bytes but $pos")
      else ByteString.fromArrayUnsafe(buffer)
  }
  private object HeaderBuilder {
    def apply(payloadSize: Int): HeaderBuilder = new HeaderBuilder(payloadSize)
  }

  def renderFrame(tpe: FrameType, flags: ByteFlag, streamId: Int, payload: ByteString): ByteString =
    HeaderBuilder(payload.size)
      .putHeader(tpe, flags, streamId)
      .put(payload)
      .result()
}
