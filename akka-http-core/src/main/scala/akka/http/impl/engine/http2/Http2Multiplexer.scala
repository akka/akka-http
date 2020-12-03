/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.annotation.InternalApi
import akka.event.LoggingAdapter
import akka.http.impl.engine.http2.FrameEvent._
import akka.http.scaladsl.settings.Http2CommonSettings
import akka.macros.LogHelper
import akka.stream.stage.{ GraphStageLogic, OutHandler, StageLogging }

import scala.collection.immutable

/**
 * INTERNAL API
 *
 * The internal interface Http2ServerDemux uses to drive the multiplexer.
 */
@InternalApi
private[http2] trait Http2Multiplexer {
  def pushControlFrame(frame: FrameEvent): Unit
  def updateConnectionLevelWindow(increment: Int): Unit
  def updateMaxFrameSize(newMaxFrameSize: Int): Unit
  def updateDefaultWindow(newDefaultWindow: Int): Unit
  def updatePriority(priorityFrame: PriorityFrame): Unit

  def enqueueOutStream(streamId: Int): Unit
  def closeStream(streamId: Int): Unit

  def currentInitialWindow: Int

  def reportTimings(): Unit

  def maxBytesToBufferPerSubstream: Int
}

@InternalApi
private[http2] sealed abstract class PullFrameResult {
  def frame: DataFrame
}
@InternalApi
private[http2] object PullFrameResult {
  final case class SendFrame(frame: DataFrame, hasMore: Boolean) extends PullFrameResult
  final case class SendFrameAndTrailer(frame: DataFrame, trailer: FrameEvent) extends PullFrameResult
}

/**
 * INTERNAL API
 *
 * Multiplexes the outgoing side of the streams on a HTTP/2 connection.
 * Accepts the streams from the Akka HTTP side and turns them into `FrameEvent`s
 * to be passed to the network side.
 *
 * The main interface between stream handling and multiplexing is this:
 *
 * - stream handling can call `enqueueOutStream` if a stream wants to send a data frame
 * - if there's connection window and the network pulls and there are no control frames to send, the multiplexer
 *   state machine calls `sendDataFrame` here. This calls `pullNextFrame` to get the next frame and more info about
 *   what the state of the stream is afterwards.
 * - stream handling can call `closeStream` to remove a potentially enqueued stream
 *
 * Mixed into the Http2ServerDemux graph logic.
 */
@InternalApi
private[http2] trait Http2MultiplexerSupport { logic: GraphStageLogic with StageLogging =>
  def isServer: Boolean
  def settings: Http2CommonSettings

  /** Allows suspending reading of frames incoming from the network */
  def allowReadingIncomingFrames(allow: Boolean): Unit

  /** Called by the multiplexer when ready to send a data frame */
  def pullNextFrame(streamId: Int, maxSize: Int): PullFrameResult

  /** Called by the multiplexer after SETTINGS_INITIAL_WINDOW_SIZE has changed */
  def distributeWindowDeltaToAllStreams(delta: Int): Unit

  /** Called by the multiplexer before canceling the stage on outlet cancellation */
  def frameOutFinished(): Unit

  def pushFrameOut(event: FrameEvent): Unit

  def createMultiplexer(prioritizer: StreamPrioritizer): Http2Multiplexer with OutHandler =
    new Http2Multiplexer with OutHandler with StateTimingSupport with LogHelper { self =>
      def log: LoggingAdapter = logic.log

      private var _currentInitialWindow: Int = Http2Protocol.InitialWindowSize
      override def currentInitialWindow: Int = _currentInitialWindow

      private var currentMaxFrameSize: Int = Http2Protocol.InitialMaxFrameSize
      private var connectionWindowLeft: Int = Http2Protocol.InitialWindowSize

      override def pushControlFrame(frame: FrameEvent): Unit = updateState(_.pushControlFrame(frame))

      def updateConnectionLevelWindow(increment: Int): Unit = {
        connectionWindowLeft += increment
        debug(s"Updating outgoing connection window by $increment to $connectionWindowLeft")
        updateState(_.connectionWindowAvailable())
      }
      override def updateMaxFrameSize(newMaxFrameSize: Int): Unit = currentMaxFrameSize = newMaxFrameSize
      override def updateDefaultWindow(newDefaultWindow: Int): Unit = {
        val delta = newDefaultWindow - _currentInitialWindow
        _currentInitialWindow = newDefaultWindow

        distributeWindowDeltaToAllStreams(delta)
      }
      override def updatePriority(info: PriorityFrame): Unit = prioritizer.updatePriority(info)

      def enqueueOutStream(streamId: Int): Unit = updateState(_.enqueueOutStream(streamId))
      def closeStream(streamId: Int): Unit = updateState(_.closeStream(streamId))

      /** Network pulls in new frames */
      def onPull(): Unit = updateState(_.onPull())

      override def onDownstreamFinish(): Unit = {
        frameOutFinished()
        super.onDownstreamFinish()
      }

      private var _state: MultiplexerState = Idle

      private def updateState(transition: MultiplexerState => MultiplexerState): Unit = {
        val oldState = _state
        val newState = transition(_state)
        _state = newState

        if (newState.name != oldState.name) recordStateChange(oldState.name, newState.name)
      }

      private[http2] sealed trait MultiplexerState extends Product {
        def name: String = productPrefix

        def onPull(): MultiplexerState
        def pushControlFrame(frame: FrameEvent): MultiplexerState
        def connectionWindowAvailable(): MultiplexerState
        def enqueueOutStream(streamId: Int): MultiplexerState
        def closeStream(streamId: Int): MultiplexerState

        protected def sendDataFrame(streamId: Int, sendableOutstreams: immutable.Set[Int]): MultiplexerState = {
          val maxBytesToSend = currentMaxFrameSize min connectionWindowLeft
          val result = pullNextFrame(streamId, maxBytesToSend)
          val frame = result.frame
          pushFrameOut(frame)
          connectionWindowLeft -= frame.payload.size

          result match {
            case PullFrameResult.SendFrame(_, hasMore) =>
              if (hasMore) WaitingForNetworkToSendData(sendableOutstreams + streamId)
              else {
                val remainingStreams = sendableOutstreams - streamId
                if (remainingStreams.isEmpty) Idle
                else WaitingForNetworkToSendData(remainingStreams)
              }
            case PullFrameResult.SendFrameAndTrailer(_, trailer) =>
              WaitingForNetworkToSendControlFrames(Vector(trailer), sendableOutstreams - streamId)
          }
        }
      }

      // Multiplexer state machine
      // Idle: No data to send, no demand from the network (i.e. we were not yet pulled)
      // WaitingForData: Got demand from the network but no data to send
      // WaitingForNetworkToSendControlFrames: Control frames (and maybe data frames) are queued but there is no network demand
      // WaitingForNetworkToSendData: Data frames queued but no network demand
      // WaitingForConnectionWindow: Data frames queued, demand from the network, but no connection-level window available

      private[http2] case object Idle extends MultiplexerState {
        def onPull(): MultiplexerState = WaitingForData
        def pushControlFrame(frame: FrameEvent): MultiplexerState = WaitingForNetworkToSendControlFrames(Vector(frame), immutable.TreeSet.empty)
        def connectionWindowAvailable(): MultiplexerState = this
        def enqueueOutStream(streamId: Int): MultiplexerState = WaitingForNetworkToSendData(immutable.TreeSet(streamId))
        def closeStream(streamId: Int): MultiplexerState = this
      }

      case object WaitingForData extends MultiplexerState {
        def onPull(): MultiplexerState = throw new IllegalStateException(s"pull unexpected while waiting for data")
        def pushControlFrame(frame: FrameEvent): MultiplexerState = {
          pushFrameOut(frame)
          Idle
        }
        def connectionWindowAvailable(): MultiplexerState = this // nothing to do, as there is no data to send
        def enqueueOutStream(streamId: Int): MultiplexerState =
          if (connectionWindowLeft == 0) WaitingForConnectionWindow(immutable.TreeSet(streamId))
          else sendDataFrame(streamId, Set.empty)
        def closeStream(streamId: Int): MultiplexerState = this
      }

      /** Not yet pulled but data waiting to be sent */
      private[http2] case class WaitingForNetworkToSendControlFrames(controlFrameBuffer: immutable.Vector[FrameEvent], sendableOutstreams: immutable.Set[Int]) extends MultiplexerState {
        require(controlFrameBuffer.nonEmpty)
        allowReadingIncomingFrames(controlFrameBuffer.size < settings.outgoingControlFrameBufferSize)
        def onPull(): MultiplexerState = controlFrameBuffer match {
          case first +: remaining =>
            pushFrameOut(first)
            allowReadingIncomingFrames(remaining.size < settings.outgoingControlFrameBufferSize)
            if (remaining.isEmpty && sendableOutstreams.isEmpty) Idle
            else if (remaining.isEmpty) WaitingForNetworkToSendData(sendableOutstreams)
            else copy(remaining, sendableOutstreams)
        }
        def pushControlFrame(frame: FrameEvent): MultiplexerState = copy(controlFrameBuffer = controlFrameBuffer :+ frame)
        def connectionWindowAvailable(): MultiplexerState = this
        def enqueueOutStream(streamId: Int): MultiplexerState =
          if (!sendableOutstreams.contains(streamId))
            copy(sendableOutstreams = sendableOutstreams + streamId)
          else
            this

        def closeStream(streamId: Int): MultiplexerState =
          if (sendableOutstreams.contains(streamId)) {
            val sendableExceptClosed = sendableOutstreams - streamId
            copy(sendableOutstreams = sendableExceptClosed)
          } else
            this
      }

      private[http2] abstract class WithSendableOutStreams extends MultiplexerState {
        def sendableOutstreams: immutable.Set[Int]
        def withSendableOutstreams(sendableOutStreams: immutable.Set[Int]): WithSendableOutStreams

        protected def sendNext(): MultiplexerState = {
          val chosenId = prioritizer.chooseSubstream(sendableOutstreams)
          sendDataFrame(chosenId, sendableOutstreams)
        }

        def closeStream(streamId: Int): MultiplexerState =
          if (sendableOutstreams.contains(streamId)) {
            val sendableExceptClosed = sendableOutstreams - streamId

            if (sendableExceptClosed.isEmpty)
              if (pulled) WaitingForData else Idle
            else withSendableOutstreams(sendableExceptClosed)
          } else
            this

        def pulled: Boolean
      }

      private[http2] case class WaitingForNetworkToSendData(sendableOutstreams: immutable.Set[Int]) extends WithSendableOutStreams {
        require(sendableOutstreams.nonEmpty)
        def onPull(): MultiplexerState =
          if (connectionWindowLeft > 0) sendNext()
          else // do nothing and wait for window first
            WaitingForConnectionWindow(sendableOutstreams)

        def pushControlFrame(frame: FrameEvent): MultiplexerState = WaitingForNetworkToSendControlFrames(Vector(frame), sendableOutstreams)
        def connectionWindowAvailable(): MultiplexerState = this
        def enqueueOutStream(streamId: Int): MultiplexerState =
          if (!sendableOutstreams.contains(streamId))
            copy(sendableOutstreams = sendableOutstreams + streamId)
          else
            this

        def withSendableOutstreams(sendableOutStreams: Set[Int]) =
          WaitingForNetworkToSendData(sendableOutStreams)

        override def pulled = false
      }

      /** Pulled and data is pending but no connection-level window available */
      private[http2] case class WaitingForConnectionWindow(sendableOutstreams: immutable.Set[Int]) extends WithSendableOutStreams {
        require(sendableOutstreams.nonEmpty)
        def onPull(): MultiplexerState = throw new IllegalStateException(s"pull unexpected while waiting for connection window")
        def pushControlFrame(frame: FrameEvent): MultiplexerState = {
          pushFrameOut(frame)
          WaitingForNetworkToSendData(sendableOutstreams)
        }
        def connectionWindowAvailable(): MultiplexerState = sendNext()
        def enqueueOutStream(streamId: Int): MultiplexerState =
          if (!sendableOutstreams.contains(streamId))
            copy(sendableOutstreams = sendableOutstreams + streamId)
          else
            this

        def withSendableOutstreams(sendableOutStreams: Set[Int]) =
          WaitingForConnectionWindow(sendableOutStreams)

        override def pulled = true
      }

      def maxBytesToBufferPerSubstream = 2 * currentMaxFrameSize // for now, let's buffer two frames per substream
    }

  private trait StateTimingSupport { self: LogHelper =>
    var timings = Map.empty[String, Long].withDefaultValue(0L)
    var lastTimestamp = System.nanoTime()

    def recordStateChange(oldState: String, newState: String): Unit = {
      val now = System.nanoTime()
      val lasted = now - lastTimestamp
      val name = oldState
      timings = timings.updated(name, timings(name) + lasted)
      lastTimestamp = now
      debug(s"Changing state from $oldState to $newState")
    }

    /** Logs DEBUG level timing data for the output side of the multiplexer*/
    def reportTimings(): Unit = {
      val timingsReport = timings.toSeq.sortBy(_._1).map {
        case (name, nanos) => f"${nanos / 1000000}%5d ms $name"
      }.mkString("\n")
      debug(s"Timing data for connection\n$timingsReport")
    }
  }
}
