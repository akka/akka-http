/*
 * Copyright (C) 2009-2025 Lightbend Inc. <https://akka.io>
 */

package akka.http.impl.engine.ws

import akka.NotUsed
import akka.util.ByteString
import akka.stream.KillSwitches
import akka.stream.SharedKillSwitch
import akka.stream.scaladsl.{ Flow, Source }
import Protocol.Opcode
import akka.annotation.InternalApi
import akka.http.scaladsl.model.ws._

import scala.concurrent.duration.FiniteDuration

/**
 * Renders messages to full frames.
 *
 * INTERNAL API
 */
@InternalApi
private[http] object MessageToFrameRenderer {
  def create(serverSide: Boolean, closeTimeout: FiniteDuration): Flow[Message, FrameStart, NotUsed] = {
    def strictFrames(opcode: Opcode, data: ByteString): Source[FrameStart, _] =
      // FIXME: fragment?
      Source.single(FrameEvent.fullFrame(opcode, None, data, fin = true))

    def streamedFrames[M](opcode: Opcode, data: Source[ByteString, M], inFlight: SharedKillSwitch): Source[FrameStart, Any] =
      data.via(inFlight.flow).statefulMap(() => true)((isFirst, data) => {
        val frameOpcode = if (isFirst) opcode else Opcode.Continuation
        (false, FrameEvent.fullFrame(frameOpcode, None, data, fin = false))
      }, _ => None) ++ Source.single(FrameEvent.emptyLastContinuationFrame)

    Flow.fromMaterializer { (mat, _) =>
      val inFlight = KillSwitches.shared("ws-in-flight-message")
      Flow[Message]
        .map[Source[FrameStart, Any]] {
          case BinaryMessage.Strict(data) => strictFrames(Opcode.Binary, data)
          case bm: BinaryMessage          => streamedFrames(Opcode.Binary, bm.dataStream, inFlight)
          case TextMessage.Strict(text)   => strictFrames(Opcode.Text, ByteString(text, "UTF-8"))
          case tm: TextMessage            => streamedFrames(Opcode.Text, tm.textStream.via(Utf8Encoder), inFlight)
        }
        // close after an ongoing streamed message rather than cutting it off, but only wait closeTimeout for it
        .recover {
          case close: WebSocketCloseException =>
            mat.scheduleOnce(closeTimeout, () => inFlight.abort(close))
            Source.failed(close)
        }
        .flatMapConcat(identity)
    }.mapMaterializedValue(_ => NotUsed)
  }
}
