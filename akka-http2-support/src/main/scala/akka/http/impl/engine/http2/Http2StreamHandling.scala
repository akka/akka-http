/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.annotation.InternalApi
import akka.http.impl.engine.http2.Http2Protocol.ErrorCode
import akka.http.scaladsl.model.http2.PeerClosedStreamException
import akka.http.scaladsl.settings.Http2CommonSettings
import akka.stream.scaladsl.Source
import akka.stream.stage.{ GraphStageLogic, OutHandler, StageLogging }
import akka.util.ByteString

import scala.collection.immutable
import FrameEvent._

import scala.util.control.NoStackTrace

/**
 * INTERNAL API
 *
 * Handles the 'incoming' side of HTTP/2 streams.
 * Accepts `FrameEvent`s from the network side and emits `ByteHttp2SubStream`s for streams
 * to be handled by the Akka HTTP layer.
 *
 * Mixed into the Http2ServerDemux graph logic.
 */
@InternalApi
private[http2] trait Http2StreamHandling { self: GraphStageLogic with StageLogging =>
  // required API from demux
  def multiplexer: Http2Multiplexer
  def settings: Http2CommonSettings
  def pushGOAWAY(errorCode: ErrorCode, debug: String): Unit
  def dispatchSubstream(sub: Http2SubStream): Unit
  def isUpgraded: Boolean

  def flowController: IncomingFlowController = IncomingFlowController.default(settings)

  private var incomingStreams = new immutable.TreeMap[Int, IncomingStreamState]
  private var largestIncomingStreamId = 0
  private var outstandingConnectionLevelWindow = Http2Protocol.InitialWindowSize
  private var totalBufferedData = 0

  private def streamFor(streamId: Int): IncomingStreamState =
    incomingStreams.get(streamId) match {
      case Some(state) => state
      case None =>
        if (streamId <= largestIncomingStreamId) Closed // closed streams are never put into the map
        else if (isUpgraded && streamId == 1) {
          // Stream 1 is implicitly "half-closed" from the client toward the server (see Section 5.1), since the request is completed as an HTTP/1.1 request
          // https://http2.github.io/http2-spec/#discover-http
          largestIncomingStreamId = streamId
          incomingStreams += streamId -> HalfClosedRemote
          HalfClosedRemote
        } else {
          largestIncomingStreamId = streamId
          incomingStreams += streamId -> Idle
          Idle
        }
    }
  def handleStreamEvent(e: StreamFrameEvent): Unit = {
    updateState(e.streamId, _.handle(e))
  }

  def handleOutgoingCreated(stream: Http2SubStream): Unit =
    updateState(stream.streamId, _.handleOutgoingCreated(stream))

  // Called by the outgoing stream multiplexer when that side of the stream is ended.
  def handleOutgoingEnded(streamId: Int): Unit = {
    updateState(streamId, _.handleOutgoingEnded())
  }

  private def updateState(streamId: Int, handle: IncomingStreamState => IncomingStreamState): Unit = {
    val oldState = streamFor(streamId)
    val newState = handle(oldState)
    newState match {
      case Closed   => incomingStreams -= streamId
      case newState => incomingStreams += streamId -> newState
    }
    log.debug(s"Incoming side of stream [$streamId] changed state: ${oldState.stateName} -> ${newState.stateName}")
  }
  /** Called to cleanup any state when the connection is torn down */
  def shutdownStreamHandling(): Unit = incomingStreams.keys.foreach(id => updateState(id, _.shutdown()))
  def resetStream(streamId: Int, errorCode: ErrorCode): Unit = {
    incomingStreams -= streamId
    log.debug(s"Incoming side of stream [$streamId]: resetting with code [$errorCode]")
    multiplexer.pushControlFrame(RstStreamFrame(streamId, errorCode))
  }

  /**
   * https://http2.github.io/http2-spec/#StreamStates
   */
  sealed abstract class IncomingStreamState { _: Product =>
    def handle(event: StreamFrameEvent): IncomingStreamState

    def stateName: String = productPrefix
    def handleOutgoingCreated(stream: Http2SubStream): IncomingStreamState = {
      log.warning(s"handleOutgoingCreated received unexpectedly in state $stateName. This indicates a bug in Akka HTTP, please report it to the issue tracker.")
      this
    }
    def handleOutgoingEnded(): IncomingStreamState = {
      log.warning(s"handleOutgoingEnded received unexpectedly in state $stateName. This indicates a bug in Akka HTTP, please report it to the issue tracker.")
      this
    }
    def receivedUnexpectedFrame(e: StreamFrameEvent): IncomingStreamState = {
      pushGOAWAY(ErrorCode.PROTOCOL_ERROR, s"Received unexpected frame of type ${e.frameTypeName} for stream ${e.streamId} in state $stateName")
      Closed
    }

    protected def expectIncomingStream(
      event:           StreamFrameEvent,
      nextStateEmpty:  IncomingStreamState,
      nextStateStream: IncomingStreamBuffer => IncomingStreamState,
      mapStream:       Http2SubStream => Http2SubStream            = identity): IncomingStreamState =
      event match {
        case frame @ ParsedHeadersFrame(streamId, endStream, _, _) =>
          val (data, nextState) =
            if (endStream)
              (Source.empty, nextStateEmpty)
            else {
              val subSource = new SubSourceOutlet[ByteString](s"substream-out-$streamId")
              (Source.fromGraph(subSource.source), nextStateStream(new IncomingStreamBuffer(streamId, subSource)))
            }

          // FIXME: after multiplexer PR is merged
          // prioInfo.foreach(multiplexer.updatePriority)
          dispatchSubstream(mapStream(ByteHttp2SubStream(frame, data)))
          nextState

        case x => receivedUnexpectedFrame(x)
      }

    /** Called to cleanup any state when the connection is torn down */
    def shutdown(): IncomingStreamState
  }
  case object Idle extends IncomingStreamState {
    def handle(event: StreamFrameEvent): IncomingStreamState = expectIncomingStream(event, HalfClosedRemote, ReceivingDataFirst)
    override def handleOutgoingCreated(stream: Http2SubStream): IncomingStreamState = SendingData(stream)
    override def shutdown(): IncomingStreamState = Closed
  }
  case class ReceivingDataFirst(buffer: IncomingStreamBuffer) extends ReceivingData(HalfClosedRemote) {
    override def handleOutgoingCreated(stream: Http2SubStream): IncomingStreamState = Open(buffer)
    override def handleOutgoingEnded(): IncomingStreamState = Closed

    override protected def onReset(streamId: Int): Unit = multiplexer.cancelSubStream(streamId)
  }
  case class SendingData(outgoingStream: Http2SubStream) extends IncomingStreamState {
    override def handle(event: StreamFrameEvent): IncomingStreamState = expectIncomingStream(event, HalfClosedRemote, Open, _.withCorrelationAttributes(outgoingStream.correlationAttributes))
    override def handleOutgoingEnded(): IncomingStreamState = SendingDataHalfClosedLocal(outgoingStream)
    override def shutdown(): IncomingStreamState = Closed
  }
  case class SendingDataHalfClosedLocal(outgoingStream: Http2SubStream) extends IncomingStreamState {
    override def handle(event: StreamFrameEvent): IncomingStreamState = expectIncomingStream(event, Closed, HalfClosedLocal, _.withCorrelationAttributes(outgoingStream.correlationAttributes))
    override def shutdown(): IncomingStreamState = Closed
  }
  sealed abstract class ReceivingData(afterEndStreamReceived: IncomingStreamState) extends IncomingStreamState { _: Product =>
    protected def buffer: IncomingStreamBuffer
    def handle(event: StreamFrameEvent): IncomingStreamState = event match {
      case d: DataFrame =>
        outstandingConnectionLevelWindow -= d.sizeInWindow
        totalBufferedData += d.payload.size // padding can be seen as instantly discarded

        if (outstandingConnectionLevelWindow < 0) {
          pushGOAWAY(ErrorCode.FLOW_CONTROL_ERROR, "Received more data than connection-level window would allow")
          Closed
        } else {
          val windowSizeIncrement = flowController.onConnectionDataReceived(outstandingConnectionLevelWindow, totalBufferedData)
          if (windowSizeIncrement > 0) {
            multiplexer.pushControlFrame(WindowUpdateFrame(Http2Protocol.NoStreamId, windowSizeIncrement))
            outstandingConnectionLevelWindow += windowSizeIncrement
          }

          buffer.onDataFrame(d).getOrElse(
            maybeFinishStream(d.endStream))
        }
      case r: RstStreamFrame =>
        buffer.onRstStreamFrame(r)
        onReset(r.streamId)
        Closed

      case h: ParsedHeadersFrame =>
        // ignored

        if (h.endStream) {
          buffer.onDataFrame(DataFrame(h.streamId, endStream = true, ByteString.empty)) // simulate end stream by empty dataframe
          log.debug(s"Ignored trailing HEADERS frame: $h")
        } else pushGOAWAY(Http2Protocol.ErrorCode.PROTOCOL_ERROR, "Got unexpected mid-stream HEADERS frame")

        maybeFinishStream(h.endStream)

      case _ => throw new IllegalStateException(s"Unexpected frame type ${event.frameTypeName} in state ${this.getClass.getName}.")
    }
    protected def onReset(streamId: Int): Unit

    protected def maybeFinishStream(endStream: Boolean): IncomingStreamState =
      if (endStream) afterEndStreamReceived else this

    override def shutdown(): IncomingStreamState = {
      buffer.shutdown()
      Closed
    }
  }

  // on the incoming side there's (almost) no difference between Open and HalfClosedLocal
  case class Open(buffer: IncomingStreamBuffer) extends ReceivingData(HalfClosedRemote) {
    override def handleOutgoingEnded(): IncomingStreamState = HalfClosedLocal(buffer)

    override protected def onReset(streamId: Int): Unit =
      multiplexer.cancelSubStream(streamId)
  }
  /**
   * We have closed the outgoing stream, but the incoming stream is still going.
   */
  case class HalfClosedLocal(buffer: IncomingStreamBuffer) extends ReceivingData(Closed) {
    override protected def onReset(streamId: Int): Unit = {
      // nothing further to do as we're already half-closed
    }
  }

  /**
   * They have closed the incoming stream, but the outgoing stream is still going.
   */
  case object HalfClosedRemote extends IncomingStreamState {
    def handle(event: StreamFrameEvent): IncomingStreamState = event match {
      case r: RstStreamFrame =>
        multiplexer.cancelSubStream(r.streamId)
        Closed
      case _ => receivedUnexpectedFrame(event)
    }

    override def handleOutgoingCreated(stream: Http2SubStream): IncomingStreamState =
      // other side has sent its complete message and now we use the channel for the response
      this
    override def handleOutgoingEnded(): IncomingStreamState = Closed
    override def shutdown(): IncomingStreamState = Closed
  }
  case object Closed extends IncomingStreamState {

    override def handleOutgoingCreated(stream: Http2SubStream): IncomingStreamState = this
    override def handleOutgoingEnded(): IncomingStreamState = this

    def handle(event: StreamFrameEvent): IncomingStreamState = event match {
      // https://http2.github.io/http2-spec/#StreamStates
      // Endpoints MUST ignore WINDOW_UPDATE or RST_STREAM frames received in this state,
      case _: RstStreamFrame | _: WindowUpdateFrame =>
        this
      case _ =>
        receivedUnexpectedFrame(event)
    }

    override def shutdown(): IncomingStreamState = this
  }

  class IncomingStreamBuffer(streamId: Int, outlet: SubSourceOutlet[ByteString]) extends OutHandler {
    private var buffer: ByteString = ByteString.empty
    private var wasClosed: Boolean = false
    private var outstandingStreamWindow: Int = Http2Protocol.InitialWindowSize // adapt if we negotiate greater sizes by settings
    outlet.setHandler(this)

    def onPull(): Unit = dispatchNextChunk()
    override def onDownstreamFinish(): Unit = {
      log.debug(s"Incoming side of stream [$streamId]: cancelling because downstream finished")
      multiplexer.pushControlFrame(RstStreamFrame(streamId, ErrorCode.CANCEL))
      incomingStreams -= streamId
    }

    def onDataFrame(data: DataFrame): Option[IncomingStreamState] = {
      if (data.endStream) wasClosed = true

      outstandingStreamWindow -= data.sizeInWindow
      if (outstandingStreamWindow < 0) {
        multiplexer.pushControlFrame(RstStreamFrame(streamId, ErrorCode.FLOW_CONTROL_ERROR))
        // also close response delivery if that has already started
        multiplexer.cancelSubStream(streamId)
        Some(Closed)
      } else {
        buffer ++= data.payload
        log.debug(s"Received DATA ${data.sizeInWindow} for stream [$streamId], remaining window space now $outstandingStreamWindow, buffered: ${buffer.size}")
        dispatchNextChunk()
        None // don't change state
      }
    }
    def onRstStreamFrame(rst: RstStreamFrame): Unit = {
      outlet.fail(new PeerClosedStreamException(rst.streamId, rst.errorCode))
      buffer = ByteString.empty
      wasClosed = true
    }

    private def dispatchNextChunk(): Unit = {
      if (buffer.nonEmpty && outlet.isAvailable) {
        val dataSize = buffer.size min settings.requestEntityChunkSize
        outlet.push(buffer.take(dataSize))
        buffer = buffer.drop(dataSize)

        totalBufferedData -= dataSize

        log.debug(s"Dispatched chunk of $dataSize for stream [$streamId], remaining window space now $outstandingStreamWindow, buffered: ${buffer.size}")
        updateWindows()
      }
      if (buffer.isEmpty && wasClosed) outlet.complete()
    }

    private def updateWindows(): Unit = {
      val IncomingFlowController.WindowIncrements(connectionLevel, streamLevel) = flowController.onStreamDataDispatched(
        outstandingConnectionLevelWindow, totalBufferedData,
        outstandingStreamWindow, buffer.size)

      if (connectionLevel > 0) {
        multiplexer.pushControlFrame(WindowUpdateFrame(Http2Protocol.NoStreamId, connectionLevel))
        outstandingConnectionLevelWindow += connectionLevel
      }
      if (streamLevel > 0 && !wasClosed /* No reason to update window on closed streams */ ) {
        multiplexer.pushControlFrame(WindowUpdateFrame(streamId, streamLevel))
        outstandingStreamWindow += streamLevel
      }

      log.debug(
        s"adjusting con-level window by $connectionLevel, stream-level window by $streamLevel, " +
          s"remaining window space now $outstandingStreamWindow, buffered: ${buffer.size}, " +
          s"remaining connection window space now $outstandingConnectionLevelWindow, total buffered: $totalBufferedData")
    }

    def shutdown(): Unit = outlet.fail(Http2StreamHandling.ConnectionWasAbortedException)
  }

  // needed once PUSH_PROMISE support was added
  //case object ReservedLocal extends IncomingStreamState
  //case object ReservedRemote extends IncomingStreamState
}
private[http2] object Http2StreamHandling {
  val ConnectionWasAbortedException = new IllegalStateException("The HTTP/2 connection was shut down while the request was still ongoing") with NoStackTrace
}
