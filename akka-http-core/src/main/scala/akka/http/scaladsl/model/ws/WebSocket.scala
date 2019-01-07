/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.model.ws

import akka.Done
import akka.stream.Materializer
import akka.stream.scaladsl.Sink

import scala.concurrent.Future

object WebSocket {

  /**
   * When attempting to ignore incoming messages from the client-side on a WebSocket connection,
   * use this Sink instead of `Sink.ignore` since this one will also properly drain incoming [[ws.BinaryMessage.Streamed]]
   * and [[TextMessage.Streamed]] messages.
   */
  def ignoreSink(implicit mat: Materializer): Sink[Message, Future[Done]] =
    Sink.foreach[Message] {
      case s: TextMessage.Streamed   => s.textStream.runWith(Sink.ignore)
      case s: BinaryMessage.Streamed => s.dataStream.runWith(Sink.ignore)
      case _                         => // otherwise it is a Strict message, so we don't need to drain it
    }

}
