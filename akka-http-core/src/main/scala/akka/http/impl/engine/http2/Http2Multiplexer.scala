/*
 * Copyright (C) 2009-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.annotation.InternalApi
import akka.event.LoggingAdapter
import akka.http.impl.engine.http2.FrameEvent._
import akka.http.scaladsl.settings.Http2CommonSettings
import akka.macros.LogHelper
import akka.stream.stage.GraphStageLogic
import akka.stream.stage.OutHandler
import akka.stream.stage.StageLogging
import scala.annotation.nowarn

import scala.collection.mutable

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

  def hasFlushedAllData: Boolean
}

@InternalApi
private[http2] sealed abstract class PullFrameResult
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

  def onAllDataFlushed(): Unit

  def createMultiplexer(prioritizer: StreamPrioritizer): Http2Multiplexer with OutHandler =
    new Http2Multiplexer with OutHandler with StateTimingSupport with LogHelper { self =>
      def log: LoggingAdapter = logic.log
      // cache debug state at the beginning to avoid that this has to be queried all the time
      override lazy val isDebugEnabled: Boolean = super.isDebugEnabled

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

      def hasFlushedAllData: Boolean = allDataFlushed(_state)
      private def allDataFlushed(state: MultiplexerState): Boolean = (state eq WaitingForData) || (state eq Idle)

      private val controlFrameBuffer: mutable.Queue[FrameEvent] = new mutable.Queue[FrameEvent]
      private val sendableOutstreams: mutable.Queue[Int] = new mutable.Queue[Int]
      private def enqueueStream(streamId: Int): Unit = {
        if (isDebugEnabled) require(!sendableOutstreams.contains(streamId), s"Stream [$streamId] was enqueued multiple times.") // requires expensive scanning -> avoid in production
        sendableOutstreams.enqueue(streamId)
      }
      private def dequeueStream(streamId: Int): Unit =
        sendableOutstreams -= streamId

      private def updateState(transition: MultiplexerState => MultiplexerState): Unit = {
        val oldState = _state
        val newState = transition(_state)
        _state = newState

        if (isDebugEnabled && newState.name != oldState.name) recordStateChange(oldState.name, newState.name)
        if (allDataFlushed(newState)) onAllDataFlushed()
        allowReadingIncomingFrames(controlFrameBuffer.size < settings.outgoingControlFrameBufferSize)
      }

      sealed trait MultiplexerState extends Product {
        def name: String = productPrefix

        def onPull(): MultiplexerState
        @nowarn("msg=references private")
        def pushControlFrame(frame: FrameEvent): MultiplexerState
        def connectionWindowAvailable(): MultiplexerState
        def enqueueOutStream(streamId: Int): MultiplexerState
        def closeStream(streamId: Int): MultiplexerState

        protected def sendDataFrame(streamId: Int): MultiplexerState = {
          val maxBytesToSend = currentMaxFrameSize min connectionWindowLeft
          val result = pullNextFrame(streamId, maxBytesToSend)
          def send(frame: DataFrame): Unit = {
            pushFrameOut(frame)
            connectionWindowLeft -= frame.payload.length
          }

          result match {
            case PullFrameResult.SendFrame(frame, hasMore) =>
              send(frame)
              if (hasMore) {
                enqueueStream(streamId)
                WaitingForNetworkToSendData
              } else {
                if (sendableOutstreams.isEmpty) Idle
                else WaitingForNetworkToSendData
              }
            case PullFrameResult.SendFrameAndTrailer(frame, trailer) =>
              send(frame)
              controlFrameBuffer += trailer
              WaitingForNetworkToSendControlFrames
          }
        }
      }

      // Multiplexer state machine
      // Idle: No data to send, no demand from the network (i.e. we were not yet pulled)
      // WaitingForData: Got demand from the network but no data to send
      // WaitingForNetworkToSendControlFrames: Control frames (and maybe data frames) are queued but there is no network demand
      // WaitingForNetworkToSendData: Data frames queued but no network demand
      // WaitingForConnectionWindow: Data frames queued, demand from the network, but no connection-level window available

      case object Idle extends MultiplexerState {
        def onPull(): MultiplexerState = WaitingForData
        def pushControlFrame(frame: FrameEvent): MultiplexerState = {
          controlFrameBuffer += frame
          WaitingForNetworkToSendControlFrames
        }
        def connectionWindowAvailable(): MultiplexerState = this
        def enqueueOutStream(streamId: Int): MultiplexerState = {
          enqueueStream(streamId)
          WaitingForNetworkToSendData
        }
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
          if (connectionWindowLeft == 0) {
            enqueueStream(streamId)
            WaitingForConnectionWindow
          } else sendDataFrame(streamId)
        def closeStream(streamId: Int): MultiplexerState = this
      }

      /** Not yet pulled but data waiting to be sent */
      case object WaitingForNetworkToSendControlFrames extends MultiplexerState {
        def onPull(): MultiplexerState = {
          val first = controlFrameBuffer.dequeue()
          pushFrameOut(first)
          if (controlFrameBuffer.isEmpty && sendableOutstreams.isEmpty) Idle
          else if (controlFrameBuffer.isEmpty) WaitingForNetworkToSendData
          else this
        }
        def pushControlFrame(frame: FrameEvent): MultiplexerState = {
          controlFrameBuffer += frame
          this
        }
        def connectionWindowAvailable(): MultiplexerState = this
        def enqueueOutStream(streamId: Int): MultiplexerState = {
          enqueueStream(streamId)
          this
        }

        def closeStream(streamId: Int): MultiplexerState = {
          // expensive operation, but only called for cancelled streams
          dequeueStream(streamId)
          this
        }
      }

      abstract class WithSendableOutStreams extends MultiplexerState {
        protected def sendNext(): MultiplexerState =
          if (prioritizer eq StreamPrioritizer.First)
            sendDataFrame(sendableOutstreams.dequeue())
          else {
            val chosenId = prioritizer.chooseSubstream(sendableOutstreams.toSet)
            // expensive operation, to be optimized when prioritizers can be configured
            dequeueStream(chosenId)
            sendDataFrame(chosenId)
          }

        def closeStream(streamId: Int): MultiplexerState = {
          // expensive operation, but only called for cancelled streams
          dequeueStream(streamId)
          if (sendableOutstreams.nonEmpty) this
          else if (pulled) WaitingForData
          else Idle
        }

        def pulled: Boolean
      }

      case object WaitingForNetworkToSendData extends WithSendableOutStreams {
        def onPull(): MultiplexerState =
          if (connectionWindowLeft > 0) sendNext()
          else // do nothing and wait for window first
            WaitingForConnectionWindow

        def pushControlFrame(frame: FrameEvent): MultiplexerState = {
          controlFrameBuffer += frame
          WaitingForNetworkToSendControlFrames
        }
        def connectionWindowAvailable(): MultiplexerState = this
        def enqueueOutStream(streamId: Int): MultiplexerState = {
          enqueueStream(streamId)
          this
        }

        override def pulled = false
      }

      /** Pulled and data is pending but no connection-level window available */
      case object WaitingForConnectionWindow extends WithSendableOutStreams {
        def onPull(): MultiplexerState = throw new IllegalStateException(s"pull unexpected while waiting for connection window")
        def pushControlFrame(frame: FrameEvent): MultiplexerState = {
          pushFrameOut(frame)
          WaitingForNetworkToSendData
        }
        def connectionWindowAvailable(): MultiplexerState = sendNext()
        def enqueueOutStream(streamId: Int): MultiplexerState = {
          enqueueStream(streamId)
          this
        }

        override def pulled = true
      }

      def maxBytesToBufferPerSubstream = 2 * currentMaxFrameSize // for now, let's buffer two frames per substream
    }

  private trait StateTimingSupport { self: LogHelper =>
    var timings = Map.empty[String, Long].withDefaultValue(0L)
    var lastTimestamp = System.nanoTime()

    def recordStateChange(oldState: String, newState: String): Unit = debug {
      val now = System.nanoTime()
      val lasted = now - lastTimestamp
      val name = oldState
      timings = timings.updated(name, timings(name) + lasted)
      lastTimestamp = now
      s"Changing state from $oldState to $newState"
    }

    /** Logs DEBUG level timing data for the output side of the multiplexer*/
    def reportTimings(): Unit = debug {
      val timingsReport = timings.toSeq.sortBy(_._1).map {
        case (name, nanos) => f"${nanos / 1000000}%5d ms $name"
      }.mkString("\n")
      s"Timing data for connection\n$timingsReport"
    }
  }
}
