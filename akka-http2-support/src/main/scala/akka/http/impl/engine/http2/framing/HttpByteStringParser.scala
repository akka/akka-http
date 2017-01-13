/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2.framing

import akka.http.impl.engine.http2.FrameEvent
import akka.stream.stage._
import akka.util.ByteString

import scala.annotation.tailrec
import scala.util.control.{ NoStackTrace, NonFatal }

/**
 * INTERNAL API
 * Basically a copy of [[akka.stream.impl.io.ByteStringParser]] but possible to be used outside of a single stage.
 */
private[akka] abstract class HttpByteStringParser(stageAccess: LinearGraphStageLogicAccess[ByteString, FrameEvent])
  extends InHandler with OutHandler {
  import HttpByteStringParser._
  import stageAccess._

  private var buffer = ByteString.empty
  private var current: ParseStep[FrameEvent] = FinishedParser
  private var acceptUpstreamFinish: Boolean = true
  private var untilCompact = CompactionThreshold

  final protected def startWith(step: ParseStep[FrameEvent]): Unit = current = step

  protected def recursionLimit: Int = 1000

  /**
   * doParse() is the main driver for the parser. It can be called from onPush, onPull and onUpstreamFinish.
   * The general logic is that invocation of this method either results in an emitted parsed element, or an indication
   * that there is more data needed.
   *
   * On completion there are various cases:
   *  - buffer is empty: parser accepts completion or fails.
   *  - buffer is non-empty, we wait for a pull. This might result in a few more onPull-push cycles, served from the
   *    buffer. This can lead to two conditions:
   *     - drained, empty buffer. This is either accepted completion (acceptUpstreamFinish) or a truncation.
   *     - parser demands more data than in buffer. This is always a truncation.
   *
   * If the return value is true the method must be called another time to continue processing.
   */
  private def doParseInner(): Boolean = try {
    if (buffer.nonEmpty) {
      val reader = new ByteReader(buffer)
      try {
        val parseResult = current.parse(reader)
        acceptUpstreamFinish = parseResult.acceptUpstreamFinish
        parseResult.result.foreach(el ⇒ push(el))

        if (parseResult.nextStep == FinishedParser) {
          completeOut()
          DontRecurse
        } else {
          buffer = reader.remainingData
          current = parseResult.nextStep

          // If this step didn't produce a result, continue parsing.
          if (parseResult.result.isEmpty)
            Recurse
          else
            DontRecurse
        }
      } catch {
        case NeedMoreData ⇒
          acceptUpstreamFinish = false
          if (current.canWorkWithPartialData) buffer = reader.remainingData

          // Not enough data in buffer and upstream is closed
          if (isInClosed()) current.onTruncation()
          else pull()

          DontRecurse
        case NonFatal(ex) ⇒
          failOut(new ParsingException(s"Parsing failed in step $current", ex))

          DontRecurse
      }
    } else {
      if (isInClosed()) {
        // Buffer is empty and upstream is done. If the current phase accepts completion we are done,
        // otherwise report truncation.
        if (acceptUpstreamFinish) completeOut()
        else current.onTruncation()
      } else pull()

      DontRecurse
    }
  } catch {
    case NonFatal(ex) ⇒
      cancel()
      failOut(ex) // we only fail in on direction, instead the entire BidiStage to allow GoAway to be sent back
      DontRecurse
  }

  @tailrec private def doParse(remainingRecursions: Int = recursionLimit): Unit =
    if (remainingRecursions == 0)
      failOut(
        new IllegalStateException(s"Parsing logic didn't produce result after $recursionLimit steps. " +
          "Aborting processing to avoid infinite cycles. In the unlikely case that the parsing logic " +
          "needs more recursion, override ParsingLogic.recursionLimit.")
      )
    else {
      val recurse = doParseInner()
      if (recurse) doParse(remainingRecursions - 1)
    }

  // Completion is handled by doParse as the buffer either gets empty after this call, or the parser requests
  // data that we can no longer provide (truncation).
  override def onPull(): Unit = doParse()

  def onPush(): Unit = {
    // Buffer management before we call doParse():
    //  - append new bytes
    //  - compact buffer if necessary
    buffer ++= grab()
    untilCompact -= 1
    if (untilCompact == 0) {
      // Compaction prevents of ever growing tree (list) of ByteString if buffer contents overlap most of the
      // time and hence keep referring to old buffer ByteStrings. Compaction is performed only once in a while
      // to reduce cost of copy.
      untilCompact = CompactionThreshold
      buffer = buffer.compact
    }
    doParse()
  }

  override def onUpstreamFinish(): Unit = {
    // If we have no pending pull from downstream, attempt to invoke the parser again. This will handle
    // truncation if necessary, or complete the stage (and maybe a final emit).
    if (isOutAvailable()) doParse()
    // Otherwise the pending pull will kick of doParse()
  }

}

/**
 * INTERNAL API
 */
private[akka] object HttpByteStringParser {

  val CompactionThreshold = 16

  private final val Recurse = true
  private final val DontRecurse = false

  /**
   * @param result - parser can return some element for downstream or return None if no element was generated in this step
   *               and parsing should immediately continue with the next step.
   * @param nextStep - next parser
   * @param acceptUpstreamFinish - if true - stream will complete when received `onUpstreamFinish`, if "false"
   *                             - onTruncation will be called
   */
  case class ParseResult[+T](
    result:               Option[FrameEvent],
    nextStep:             ParseStep[FrameEvent],
    acceptUpstreamFinish: Boolean               = true)

  trait ParseStep[+T] {
    /**
     * Must return true when NeedMoreData will clean buffer. If returns false - next pulled
     * data will be appended to existing data in buffer
     */
    def canWorkWithPartialData: Boolean = false
    def parse(reader: ByteReader): ParseResult[FrameEvent]
    def onTruncation(): Unit = throw new IllegalStateException("truncated data in ByteStringParser")
  }

  object FinishedParser extends ParseStep[Nothing] {
    override def parse(reader: ByteReader) =
      throw new IllegalStateException("no initial parser installed: you must use startWith(...)")
  }

  class ParsingException(msg: String, cause: Throwable) extends RuntimeException(msg, cause)

  val NeedMoreData = new Exception with NoStackTrace

  class ByteReader(input: ByteString) {

    private[this] var off = 0

    def hasRemaining: Boolean = off < input.size
    def remainingSize: Int = input.size - off

    def currentOffset: Int = off

    def remainingData: ByteString = input.drop(off)
    def fromStartToHere: ByteString = input.take(off)

    def take(n: Int): ByteString =
      if (off + n <= input.length) {
        val o = off
        off = o + n
        input.slice(o, off)
      } else throw NeedMoreData
    def takeAll(): ByteString = {
      val ret = remainingData
      off = input.size
      ret
    }

    def readByte(): Int =
      if (off < input.length) {
        val x = input(off)
        off += 1
        x & 0xFF
      } else throw NeedMoreData
    def readShortLE(): Int = readByte() | (readByte() << 8)
    def readIntLE(): Int = readShortLE() | (readShortLE() << 16)
    def readLongLE(): Long = (readIntLE() & 0xffffffffL) | ((readIntLE() & 0xffffffffL) << 32)

    def readShortBE(): Int = (readByte() << 8) | readByte()
    def readIntBE(): Int = (readShortBE() << 16) | readShortBE()
    def readLongBE(): Long = ((readIntBE() & 0xffffffffL) << 32) | (readIntBE() & 0xffffffffL)

    def skip(numBytes: Int): Unit =
      if (off + numBytes <= input.length) off += numBytes
      else throw NeedMoreData
    def skipZeroTerminatedString(): Unit = while (readByte() != 0) {}
  }
}
