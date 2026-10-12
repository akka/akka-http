/*
 * Copyright (C) 2009-2025 Lightbend Inc. <https://akka.io>
 */

package akka.http.javadsl.model.ws

import java.nio.charset.StandardCharsets

import akka.annotation.DoNotInherit

/**
 * Fail the outgoing message stream of a WebSocket handler with a `WebSocketCloseException` to close the WebSocket
 * with an application specific close code (3000-4999) and reason. The peer will see the close code and reason
 * (in Akka HTTP as a [[PeerClosedConnectionException]]).
 *
 * Note that failing a stream may discard messages buffered in stages after the stage that failed.
 *
 * Create instances using [[WebSocketCloseException.create]].
 */
@DoNotInherit
abstract class WebSocketCloseException(val closeCode: Int, val closeReason: String)
  extends RuntimeException(s"Closing WebSocket with code $closeCode '$closeReason'") {
  require(closeCode >= 3000 && closeCode <= 4999, s"Close code must be in the range 3000-4999 but was $closeCode")
  require(closeReason ne null, "Close reason must not be null")
  require(
    closeReason.getBytes(StandardCharsets.UTF_8).length <= WebSocketCloseException.MaxReasonBytes,
    s"Close reason must be at most ${WebSocketCloseException.MaxReasonBytes} bytes when UTF-8 encoded")
}

object WebSocketCloseException {
  // control frame payload is limited to 125 bytes, two of those are the close code
  private val MaxReasonBytes = 123

  /**
   * @param closeCode an application specific close code in the range 3000-4999
   * @param closeReason a reason, at most 123 bytes when UTF-8 encoded
   */
  def create(closeCode: Int, closeReason: String): WebSocketCloseException =
    new akka.http.scaladsl.model.ws.WebSocketCloseException(closeCode, closeReason)

  /**
   * @param closeCode an application specific close code in the range 3000-4999
   */
  def create(closeCode: Int): WebSocketCloseException =
    new akka.http.scaladsl.model.ws.WebSocketCloseException(closeCode)
}
