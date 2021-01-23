/*
 * Copyright (C) 2009-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.ws

import akka.NotUsed
import akka.annotation.InternalApi
import akka.event.Logging
import akka.stream.Attributes
import akka.stream.Attributes.LogLevels
import akka.stream.scaladsl.{ BidiFlow, Flow }
import akka.util.ByteString

/**
 * INTERNAL API
 */
@InternalApi
private[ws] object FrameLogger {
  final val maxBytes = 16

  def logFramesIfEnabled(shouldLog: Boolean): BidiFlow[FrameEvent, FrameEvent, FrameEvent, FrameEvent, NotUsed] =
    if (shouldLog) bidi
    else BidiFlow.identity

  def bidi: BidiFlow[FrameEvent, FrameEvent, FrameEvent, FrameEvent, NotUsed] =
    BidiFlow.fromFlows(
      Flow[FrameEvent].log(s"${Console.RED}DOWN${Console.RESET}", FrameLogger.logEvent),
      Flow[FrameEvent].log(s"${Console.GREEN} UP ${Console.RESET}", FrameLogger.logEvent))
      .addAttributes(Attributes(LogLevels(Logging.DebugLevel, Logging.DebugLevel, Logging.DebugLevel)))

  def logEvent(frameEvent: FrameEvent): String = {
    case class LogEntry(frameType: String, data: String)

    def hex(bytes: ByteString): String = {
      val num = math.min(maxBytes, bytes.size)
      val ellipsis = if (num < bytes.size) s" [... ${bytes.size - num} more bytes]" else ""
      bytes
        .take(num)
        .map(_ formatted "%02x")
        .mkString(" ") + ellipsis
    }

    def entryForFrame(frameEvent: FrameEvent): LogEntry =
      frameEvent match {
        case FrameStart(_, data) => LogEntry("FRAMESTART", hex(data))
        case FrameData(data, _)  => LogEntry("FRAMEDATA", hex(data))
      }

    def display(entry: LogEntry): String = {
      import entry._
      f"${Console.GREEN}$frameType%s ${Console.RESET}$data"
    }

    display(entryForFrame(frameEvent))
  }
}
