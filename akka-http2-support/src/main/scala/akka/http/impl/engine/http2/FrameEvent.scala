/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.annotation.InternalApi
import akka.http.impl.engine.http2.Http2Protocol.ErrorCode
import akka.http.impl.engine.http2.Http2Protocol.FrameType
import akka.http.impl.engine.http2.Http2Protocol.SettingIdentifier
import akka.util.ByteString

import scala.collection.immutable

/** INTERNAL API */
@InternalApi
private[http2] sealed trait FrameEvent { self: Product ⇒
  def frameTypeName: String = productPrefix
}
/** INTERNAL API */
@InternalApi
private[http] object FrameEvent {

  sealed trait StreamFrameEvent extends FrameEvent { self: Product ⇒
    def streamId: Int
  }

  final case class GoAwayFrame(lastStreamId: Int, errorCode: ErrorCode, debug: ByteString = ByteString.empty) extends FrameEvent {
    override def toString: String = s"GoAwayFrame($lastStreamId,$errorCode,debug:<hidden>)"
  }
  final case class DataFrame(
    streamId:  Int,
    endStream: Boolean,
    payload:   ByteString) extends StreamFrameEvent {
    /**
     * The amount of bytes this frame consumes of a window. According to RFC 7540, 6.9.1:
     *
     *        For flow-control calculations, the 9-octet frame header is not
     *        counted.
     *
     * That means this size amounts to data size + padding size field + padding.
     */
    def sizeInWindow: Int = payload.size // FIXME: take padding size into account, #1313
  }

  final case class HeadersFrame(
    streamId:            Int,
    endStream:           Boolean,
    endHeaders:          Boolean,
    headerBlockFragment: ByteString,
    priorityInfo:        Option[PriorityFrame]) extends StreamFrameEvent
  final case class ContinuationFrame(
    streamId:   Int,
    endHeaders: Boolean,
    payload:    ByteString) extends StreamFrameEvent
  case class PushPromiseFrame(
    streamId:            Int,
    endHeaders:          Boolean,
    promisedStreamId:    Int,
    headerBlockFragment: ByteString) extends StreamFrameEvent

  final case class RstStreamFrame(streamId: Int, errorCode: ErrorCode) extends StreamFrameEvent
  final case class SettingsFrame(settings: immutable.Seq[Setting]) extends FrameEvent
  final case class SettingsAckFrame(acked: immutable.Seq[Setting]) extends FrameEvent

  case class PingFrame(ack: Boolean, data: ByteString) extends FrameEvent {
    require(data.size == 8, s"PingFrame payload must be of size 8 but was ${data.size}")
  }
  final case class WindowUpdateFrame(
    streamId:            Int,
    windowSizeIncrement: Int) extends StreamFrameEvent

  final case class PriorityFrame(
    streamId:         Int,
    exclusiveFlag:    Boolean,
    streamDependency: Int,
    weight:           Int) extends StreamFrameEvent

  final case class Setting(
    identifier: SettingIdentifier,
    value:      Int)

  object Setting {
    implicit def autoConvertFromTuple(tuple: (SettingIdentifier, Int)): Setting =
      Setting(tuple._1, tuple._2)
  }

  /** Dummy event for all unknown frames */
  final case class UnknownFrameEvent(
    tpe:      FrameType,
    flags:    ByteFlag,
    streamId: Int,
    payload:  ByteString) extends StreamFrameEvent

  final case class ParsedHeadersFrame(
    streamId:      Int,
    endStream:     Boolean,
    keyValuePairs: Seq[(String, String)],
    priorityInfo:  Option[PriorityFrame]
  ) extends StreamFrameEvent

}
