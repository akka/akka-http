/*
 * Copyright (C) 2020-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import java.nio.ByteOrder

import akka.http.impl.engine.http2.FrameEvent.{ ContinuationFrame, HeadersFrame, PriorityFrame, Setting, SettingsFrame, WindowUpdateFrame }
import akka.http.impl.engine.http2.Http2Protocol.{ ErrorCode, Flags, FrameType, SettingIdentifier }
import akka.http.impl.engine.http2.framing.FrameRenderer
import akka.util.{ ByteString, ByteStringBuilder }

private[http2] trait Http2FrameSending {
  def sendBytes(bytes: ByteString): Unit

  def sendFrame(frame: FrameEvent): Unit =
    sendBytes(FrameRenderer.render(frame))

  def sendFrame(frameType: FrameType, flags: ByteFlag, streamId: Int, payload: ByteString): Unit =
    sendBytes(FrameRenderer.renderFrame(frameType, flags, streamId, payload))

  /** Can be overridden to also update windows */
  def sendDATA(streamId: Int, endStream: Boolean, data: ByteString): Unit =
    sendFrame(FrameType.DATA, Flags.END_STREAM.ifSet(endStream), streamId, data)

  def sendSETTING(identifier: SettingIdentifier, value: Int): Unit =
    sendFrame(SettingsFrame(Setting(identifier, value) :: Nil))

  def sendHEADERS(streamId: Int, endStream: Boolean, endHeaders: Boolean, headerBlockFragment: ByteString): Unit =
    sendBytes(FrameRenderer.render(HeadersFrame(streamId, endStream, endHeaders, headerBlockFragment, None)))

  def sendCONTINUATION(streamId: Int, endHeaders: Boolean, headerBlockFragment: ByteString): Unit =
    sendBytes(FrameRenderer.render(ContinuationFrame(streamId, endHeaders, headerBlockFragment)))

  def sendPRIORITY(streamId: Int, exclusiveFlag: Boolean, streamDependency: Int, weight: Int): Unit =
    sendBytes(FrameRenderer.render(PriorityFrame(streamId, exclusiveFlag, streamDependency, weight)))

  def sendRST_STREAM(streamId: Int, errorCode: ErrorCode): Unit = {
    implicit val bigEndian: ByteOrder = ByteOrder.BIG_ENDIAN
    val bb = new ByteStringBuilder
    bb.putInt(errorCode.id)
    sendFrame(FrameType.RST_STREAM, ByteFlag.Zero, streamId, bb.result())
  }

  /** Can be overridden to also update windows */
  def sendWINDOW_UPDATE(streamId: Int, windowSizeIncrement: Int): Unit =
    sendBytes(FrameRenderer.render(WindowUpdateFrame(streamId, windowSizeIncrement)))

}
