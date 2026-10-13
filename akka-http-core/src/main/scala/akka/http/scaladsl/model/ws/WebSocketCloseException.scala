/*
 * Copyright (C) 2009-2025 Lightbend Inc. <https://akka.io>
 */

package akka.http.scaladsl.model.ws

import akka.http.javadsl

/**
 * Fail the outgoing message stream of a WebSocket handler with a `WebSocketCloseException` to close the WebSocket
 * with an application specific close code (3000-4999) and reason. The peer will see the close code and reason
 * (in Akka HTTP as a [[PeerClosedConnectionException]]).
 *
 * Note that failing a stream may discard messages buffered in stages downstream of the failing stage. An ongoing
 * streamed message is given the close timeout (3 seconds) to finish before the WebSocket is closed.
 *
 * @param closeCode an application specific close code in the range 3000-4999
 * @param closeReason a reason, at most 123 bytes when UTF-8 encoded
 */
final class WebSocketCloseException(closeCode: Int, closeReason: String)
  extends javadsl.model.ws.WebSocketCloseException(closeCode, closeReason) {
  def this(closeCode: Int) = this(closeCode, "")
}
