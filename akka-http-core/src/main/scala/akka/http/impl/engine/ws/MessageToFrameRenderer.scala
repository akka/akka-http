/*
 * Copyright (C) 2009-2025 Lightbend Inc. <https://akka.io>
 */

package akka.http.impl.engine.ws

import akka.NotUsed
import akka.util.ByteString
import akka.stream.scaladsl.{ Flow, Source }
import Protocol.Opcode
import akka.annotation.InternalApi
import akka.http.scaladsl.model.ws._

/**
 * Renders messages to full frames.
 *
 * INTERNAL API
 */
@InternalApi
private[http] object MessageToFrameRenderer {
  def create(serverSide: Boolean): Flow[Message, FrameStart, NotUsed] = {
    def strictFrames(opcode: Opcode, data: ByteString): Source[FrameStart, _] =
      // FIXME: fragment?
      Source.single(FrameEvent.fullFrame(opcode, None, data, fin = true))

    def streamedFrames[M](opcode: Opcode, data: Source[ByteString, M]): Source[FrameStart, Any] =
      data.statefulMap(() => true)((isFirst, data) => {
        val frameOpcode = if (isFirst) opcode else Opcode.Continuation
        (false, FrameEvent.fullFrame(frameOpcode, None, data, fin = false))
      }, _ => None) ++ Source.single(FrameEvent.emptyLastContinuationFrame)

    Flow[Message]
      // flatMapConcat fails immediately on upstream failure, cutting off an ongoing streamed message,
      // lift the close into an element so that it is only rendered after previous messages
      .recover[AnyRef] { case close: WebSocketCloseException => close }
      .flatMapConcat {
        case BinaryMessage.Strict(data)     => strictFrames(Opcode.Binary, data)
        case bm: BinaryMessage              => streamedFrames(Opcode.Binary, bm.dataStream)
        case TextMessage.Strict(text)       => strictFrames(Opcode.Text, ByteString(text, "UTF-8"))
        case tm: TextMessage                => streamedFrames(Opcode.Text, tm.textStream.via(Utf8Encoder))
        case close: WebSocketCloseException => Source.failed(close)
        case other                          => throw new IllegalStateException(s"Unexpected element $other")
      }
  }
}
