/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import scala.annotation.switch

/** INTERNAL API */
private[akka] object Http2Compliance {
  def parseSettungsEnablePushValue(incomingValue: Int): Boolean =
    (incomingValue: @switch) match {
      case 0 ⇒ false
      case 1 ⇒ true
      case _ ⇒ throw new IllegalEnablePushValueSettingException(s"Illegal value in SETTINGS_ENABLE_PUSH settings header! MUST BE 0 or 1, but was: [$incomingValue]")
    }

  private val MinOutMaxFrameSize = Math.pow(2, 14).toInt // minimum allowed to be set
  private val MaxOutMaxFrameSize = (Math.pow(2, 24) - 1).toInt // maximum allowed to be set

  final class WindowOverflowException(currentWindow: Int, increment: Int)
    extends ArithmeticException(s"WINDOW overflow, must not exceed 2^31-1 nor go below 0. " +
      s"Attempt to add increment ${increment} is illegal.")

  final class IllegalHttp2StreamIdException(id: Int, expected: String)
    extends IllegalArgumentException(s"Illegal HTTP/2 stream id: [$id]. $expected!")

  final class MissingHttpIdHeaderException extends IllegalArgumentException("Expected `Http2StreamIdHeader` header to be present but was missing!")

  final class IllegalPushPromiseAttemptException
    extends IllegalStateException("Server attempted to write PUSH_PROMISE to connection which had disabled it. See spec 6.5.2")

  final class IllegalHttp2StreamDependency(id: Int)
    extends IllegalArgumentException(s"Illegal self dependency of stream for id: [$id]!")

  final class IllegalFrameSizeSettingException(msg: String) extends IllegalArgumentException(msg)

  final class IllegalEnablePushValueSettingException(msg: String) extends IllegalArgumentException(msg)

  final class HeaderDecompressionFailed(msg: String) extends IllegalStateException(msg)

  final class IllegalPayloadInSettingsAckFrame(size: Int, expected: String) extends IllegalHttp2FrameSize(size, expected)

  final class IllegalPayloadLengthInSettingsFrame(size: Int, expected: String) extends IllegalHttp2FrameSize(size, expected)

  final def missingHttpIdHeaderException = throw new MissingHttpIdHeaderException

  // @DoNotInherit
  private[akka] sealed class IllegalHttp2FrameSize(size: Int, expected: String)
    extends IllegalArgumentException(s"Illegal HTTP/2 frame size: [$size]. $expected!")

  // require methods use `if` because `require` allocates

  final def requireZeroStreamId(id: Int): Unit =
    if (id != 0) throw new IllegalHttp2StreamIdException(id, "MUST BE == 0.")

  /** checks if the stream id was client initiated, by checking if the stream id was odd-numbered */
  final def isClientInitiatedStreamId(id: Int): Boolean = id % 2 != 0

  final def requireFrameSize(size: Int, required: Int, hint: String = ""): Unit =
    if (size != required) throw new IllegalHttp2FrameSize(size, s"MUST BE == $required. $hint")

  final def requireFrameSize(size: Int, max: Int): Unit =
    if (size != max) throw new IllegalHttp2FrameSize(size, s"MUST BE == $max.")

  final def requireFrameSizeLessOrEqualThan(size: Int, max: Int, hint: String = ""): Unit =
    if (size > max) throw new IllegalHttp2FrameSize(size, s"MUST BE <= $max. $hint")

  final def requireNoSelfDependency(id: Int, dependency: Int): Unit =
    if (id == dependency) throw new IllegalHttp2StreamDependency(id)

  /** Validate value of MAX_FRAME_SIZE setting. */
  def validateMaxFrameSize(value: Int): Unit = {
    if (value < MinOutMaxFrameSize) throw new IllegalFrameSizeSettingException(s"MAX_FRAME_SIZE MUST NOT be < than $MinOutMaxFrameSize, attempted setting to: $value!")
    if (value > MaxOutMaxFrameSize) throw new IllegalFrameSizeSettingException(s"MAX_FRAME_SIZE MUST NOT be > than $MaxOutMaxFrameSize, attempted setting to: $value!")
  }

  final case class WindowSizeWithOverflowProtection(value: Int) extends AnyVal {
    def +(increment: Int): WindowSizeWithOverflowProtection = {
      val res = value.toLong + increment
      val resi = res.toInt
      if (res != resi) throw new Http2Compliance.WindowOverflowException(value, increment)
      WindowSizeWithOverflowProtection(resi)
    }

    def -(decrement: Int): WindowSizeWithOverflowProtection = {
      this + (-decrement)
    }
  }

}
