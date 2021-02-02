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
    case class LogEntry(frameType: String, length: Long, lastPart: String, data: String, flags: Option[String]*)

    def flag(value: Boolean, name: String): Option[String] = if (value) Some(name) else None
    def hex(bytes: ByteString): String = {
      val num = math.min(maxBytes, bytes.size)
      val ellipsis = if (num < bytes.size) s" [... ${bytes.size - num} more bytes]" else ""
      bytes
        .take(num)
        .map(_ formatted "%02x")
        .mkString(" ") + ellipsis
    }

    def entryForFrame(frameEvent: FrameEvent): LogEntry = {
      val lastPart = if (frameEvent.lastPart) "T" else "F"
      frameEvent match {
        case FrameStart(header, data) => LogEntry(header.opcode.toString.toUpperCase, header.length, lastPart, hex(data), flag(header.fin, "FIN"), flag(header.rsv1, "RSV1"), flag(header.rsv2, "RSV2"), flag(header.rsv3, "RSV3"))
        case FrameData(data, _)       => LogEntry("FRAMEDATA", 0, lastPart, hex(data))
      }
    }

    def display(entry: LogEntry): String = {
      import entry._
      import Console._

      f"$GREEN$frameType%s $YELLOW$length%4d $RED${flags.flatten.mkString(" ")} $BLUE$lastPart%s $RESET$data"
    }

    display(entryForFrame(frameEvent))
  }
}
