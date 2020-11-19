package akka.http.impl.engine.http2

import akka.http.impl.engine.http2.Http2Protocol.FrameType
import akka.stream.impl.io.ByteStringParser.ByteReader
import akka.util.ByteString

import scala.concurrent.duration.FiniteDuration

trait WindowTracking extends Http2FrameProbeDelegator with Http2FrameSending {
  override def sendDATA(streamId: Int, endStream: Boolean, data: ByteString): Unit = {
    updateToServerWindowForConnection(_ - data.length)
    updateToServerWindows(streamId, _ - data.length)
    super.sendDATA(streamId, endStream, data)
  }

  override def sendWINDOW_UPDATE(streamId: Int, windowSizeIncrement: Int): Unit = {
    super.sendWINDOW_UPDATE(streamId, windowSizeIncrement)
    if (streamId == 0) updateFromServerWindowForConnection(_ + windowSizeIncrement)
    else updateFromServerWindows(streamId, _ + windowSizeIncrement)
  }

  def expectWindowUpdate(): Unit =
    expectFrameFlagsStreamIdAndPayload(FrameType.WINDOW_UPDATE) match {
      case (flags, streamId, payload) =>
        // TODO: DRY up with autoFrameHandler
        val windowSizeIncrement = new ByteReader(payload).readIntBE()

        if (streamId == 0) updateToServerWindowForConnection(_ + windowSizeIncrement)
        else updateToServerWindows(streamId, _ + windowSizeIncrement)
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
  private var toServerWindows: Map[Int, Int] = Map.empty.withDefaultValue(Http2Protocol.InitialWindowSize)
  private var toServerWindowForConnection = Http2Protocol.InitialWindowSize
  def remainingToServerWindowForConnection: Int = toServerWindowForConnection
  def remainingToServerWindowFor(streamId: Int): Int = toServerWindows(streamId) min remainingToServerWindowForConnection

  def updateWindowMap(streamId: Int, update: Int => Int): Map[Int, Int] => Map[Int, Int] =
    map => map.updated(streamId, update(map(streamId)))

  def safeUpdate(update: Int => Int): Int => Int = { oldValue =>
    val newValue = update(oldValue)
    assert(newValue >= 0)
    newValue
  }

  def updateToServerWindows(streamId: Int, update: Int => Int): Unit =
    toServerWindows = updateWindowMap(streamId, safeUpdate(update))(toServerWindows)
  def updateToServerWindowForConnection(update: Int => Int): Unit =
    toServerWindowForConnection = safeUpdate(update)(toServerWindowForConnection)

}
