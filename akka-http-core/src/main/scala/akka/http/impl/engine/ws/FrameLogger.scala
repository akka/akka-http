/*
 * Copyright (C) 2009-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.ws

import akka.NotUsed
import akka.annotation.InternalApi
import akka.event.Logging
import akka.http.impl.util.LogByteStringTools
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

  def logFramesIfEnabled(shouldLog: Boolean): BidiFlow[FrameEventOrError, FrameEventOrError, FrameEvent, FrameEvent, NotUsed] =
    if (shouldLog) bidi
    else BidiFlow.identity

  def bidi: BidiFlow[FrameEventOrError, FrameEventOrError, FrameEvent, FrameEvent, NotUsed] =
    BidiFlow.fromFlows(
      Flow[FrameEventOrError].log(s"${Console.RED}DOWN${Console.RESET}", FrameLogger.logEvent),
      Flow[FrameEvent].log(s"${Console.GREEN} UP ${Console.RESET}", FrameLogger.logEvent))
      .addAttributes(Attributes(LogLevels(Logging.DebugLevel, Logging.DebugLevel, Logging.DebugLevel)))

  def logEvent(frameEvent: FrameEventOrError): String = {
    import Console._

    def displayLogEntry(frameType: String, length: Long, lastPart: String, data: String, flags: Option[String]*): String =
      f"$GREEN$frameType%s $YELLOW$length%4d $RED${flags.flatten.mkString(" ")} $BLUE$lastPart%s $RESET$data"

    def flag(value: Boolean, name: String): Option[String] = if (value) Some(name) else None
    def hex(bytes: ByteString): String = {
      val num = math.min(maxBytes, bytes.size)
      val ellipsis = if (num < bytes.size) s" [... ${bytes.size - num} more bytes]" else ""
      val first = bytes.take(num)
      val h = first.map(_ formatted "%02x").mkString(" ")
      val ascii = first.map(LogByteStringTools.asASCII).mkString
      s"$h | $ascii$ellipsis"
    }

    def entryForFrame(frameEvent: FrameEventOrError): String = frameEvent match {
      case frameEvent: FrameEvent =>
        val lastPart = if (frameEvent.lastPart) "T" else "F"
        frameEvent match {
          case FrameStart(header, data) => displayLogEntry(header.opcode.toString.toUpperCase, header.length, lastPart, hex(data), flag(header.fin, "FIN"), flag(header.rsv1, "RSV1"), flag(header.rsv2, "RSV2"), flag(header.rsv3, "RSV3"))
          case FrameData(data, _)       => displayLogEntry("FRAMEDATA", 0, lastPart, hex(data))
        }
      case FrameError(ex) =>
        f"${RED}Error: ${ex.getMessage}$RESET"
    }
    entryForFrame(frameEvent)
  }
}