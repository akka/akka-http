/*
 * Copyright (C) 2020-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.http.impl.engine.http2.Http2Protocol.FrameType
import akka.stream.impl.io.ByteStringParser.ByteReader
import akka.util.ByteString

import scala.concurrent.duration.FiniteDuration

trait WindowTracking extends Http2FrameProbeDelegator with Http2FrameSending {
  override def sendDATA(streamId: Int, endStream: Boolean, data: ByteString): Unit = {
    updateWindowForIncomingDataOnConnection(_ - data.length)
    updateWindowForIncomingData(streamId, _ - data.length)
    super.sendDATA(streamId, endStream, data)
  }

  override def sendWINDOW_UPDATE(streamId: Int, windowSizeIncrement: Int): Unit = {
    super.sendWINDOW_UPDATE(streamId, windowSizeIncrement)
    if (streamId == 0) updateFromServerWindowForConnection(_ + windowSizeIncrement)
    else updateFromServerWindows(streamId, _ + windowSizeIncrement)
  }

  def sendWindowFullOfData(streamId: Int): Int = {
    val dataLength = remainingWindowForIncomingData(streamId)
    sendDATA(streamId, endStream = false, ByteString(Array.fill[Byte](dataLength)(23)))
    dataLength
  }
  def expectWindowUpdate(): Unit =
    expectFrameFlagsStreamIdAndPayload(FrameType.WINDOW_UPDATE) match {
      case (flags, streamId, payload) =>
        // TODO: DRY up with autoFrameHandler
        val windowSizeIncrement = new ByteReader(payload).readIntBE()

        if (streamId == 0) updateWindowForIncomingDataOnConnection(_ + windowSizeIncrement)
        else updateWindowForIncomingData(streamId, _ + windowSizeIncrement)
    }

  final def pollForWindowUpdates(duration: FiniteDuration): Unit =
    try {
      within(duration)(expectWindowUpdate())

      pollForWindowUpdates(duration)
    } catch {
      case e: AssertionError if e.getMessage contains "but only got [0] bytes" =>
      // timeout, that's expected
      case e: AssertionError if (e.getMessage contains "block took") && (e.getMessage contains "exceeding") =>
        // pause like GC, poll again just to be sure
        pollForWindowUpdates(duration)
    }

  // keep counters that are updated on outgoing network.sendDATA and incoming WINDOW_UPDATE frames
  private var windowsForIncomingData: Map[Int, Int] = Map.empty.withDefaultValue(Http2Protocol.InitialWindowSize)
  private var windowForIncomingDataOnConnection = Http2Protocol.InitialWindowSize
  def remainingWindowForIncomingDataOnConnection: Int = windowForIncomingDataOnConnection
  def remainingWindowForIncomingData(streamId: Int): Int = windowsForIncomingData(streamId) min remainingWindowForIncomingDataOnConnection

  def updateWindowMap(streamId: Int, update: Int => Int): Map[Int, Int] => Map[Int, Int] =
    map => map.updated(streamId, update(map(streamId)))

  def safeUpdate(update: Int => Int): Int => Int = { oldValue =>
    val newValue = update(oldValue)
    assert(newValue >= 0)
    newValue
  }

  def updateWindowForIncomingData(streamId: Int, update: Int => Int): Unit =
    windowsForIncomingData = updateWindowMap(streamId, safeUpdate(update))(windowsForIncomingData)
  def updateWindowForIncomingDataOnConnection(update: Int => Int): Unit =
    windowForIncomingDataOnConnection = safeUpdate(update)(windowForIncomingDataOnConnection)

}
