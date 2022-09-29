/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.annotation.InternalApi
import akka.http.impl.engine.http2.FrameEvent._
import akka.http.impl.engine.http2.Http2Protocol.ErrorCode
import akka.http.impl.engine.rendering.DateHeaderRendering
import akka.http.scaladsl.model.{ AttributeKey, HttpEntity }
import akka.http.scaladsl.model.http2.PeerClosedStreamException
import akka.http.scaladsl.settings.Http2CommonSettings
import akka.macros.LogHelper
import akka.stream.scaladsl.{ Sink, Source }
import akka.stream.stage.{ GraphStageLogic, InHandler, OutHandler }
import akka.util.{ ByteString, OptionVal }

import scala.collection.mutable
import scala.util.control.NoStackTrace

/**
 * INTERNAL API
 *
 * Handles HTTP/2 stream states
 * Accepts `FrameEvent`s from the network side and emits `ByteHttp2SubStream`s for streams
 * to be handled by the Akka HTTP layer.
 *
 * Mixed into the Http2ServerDemux graph logic.
 */
@InternalApi
private[http2] trait Http2StreamHandling extends GraphStageLogic with LogHelper { self =>
  // required API from demux
  def isServer: Boolean
  def multiplexer: Http2Multiplexer
  def settings: Http2CommonSettings
  def pushGOAWAY(errorCode: ErrorCode, debug: String): Unit
  def dispatchSubstream(initialHeaders: ParsedHeadersFrame, data: Either[ByteString, Source[Any, Any]], correlationAttributes: Map[AttributeKey[_], _]): Unit
  def isUpgraded: Boolean

  def wrapTrailingHeaders(headers: ParsedHeadersFrame): Option[HttpEntity.ChunkStreamPart]

  def flowController: IncomingFlowController = IncomingFlowController.default(settings)

  /**
   * Tries to generate demand of SubStreams on the inlet from the user handler. The
   * attemp to demand will succeed if the inlet is open and has no pending pull, and,
   * in the case of a client, if we're not exceedingthe number of active streams.
   * This method must be invoked any time the collection of active streams or the
   * value of maxConcurrentStreams are modified but the invocation must happen _after_
   * the collection or the limit are modified.
   */
  def tryPullSubStreams(): Unit

  private val streamStates = new mutable.LongMap[StreamState](settings.maxConcurrentStreams)
  private var largestIncomingStreamId = 0
  private var outstandingConnectionLevelWindow = Http2Protocol.InitialWindowSize
  private var totalBufferedData = 0
  /**
   * The "last peer-initiated stream that was or might be processed on the sending endpoint in this connection"
   *
   * @see http://httpwg.org/specs/rfc7540.html#rfc.section.6.8
   */
  def lastStreamId(): Int = largestIncomingStreamId

  private var maxConcurrentStreams = Http2Protocol.InitialMaxConcurrentStreams
  def setMaxConcurrentStreams(newValue: Int): Unit = maxConcurrentStreams = newValue
  /**
   * @return true if the number of outgoing Active streams (Active includes Open
   *         and any variant of HalfClosedXxx) doesn't exceed MaxConcurrentStreams
   */
  def hasCapacityToCreateStreams: Boolean = {
    // StreamStates only contains streams in active states (active states are any variation
    // of Open, HalfClosed) so using the `size` works fine to compute the capacity
    activeStreamCount() < maxConcurrentStreams
  }

  /** Called when all streams in StreamHandling buffers are closed and the stage is completing. */
  def onAllStreamsClosed(): Unit

  private def streamFor(streamId: Int): StreamState =
    streamStates.getOrNull(streamId) match {
      case null =>
        if (streamId <= largestIncomingStreamId) Closed // closed streams are never put into the map
        else if (isUpgraded && streamId == 1) {
          require(isServer)
          // Stream 1 is implicitly "half-closed" from the client toward the server (see Section 5.1), since the request is completed as an HTTP/1.1 request
          // https://http2.github.io/http2-spec/#discover-http
          largestIncomingStreamId = streamId
          streamStates.put(streamId, HalfClosedRemoteWaitingForOutgoingStream(0))
          HalfClosedRemoteWaitingForOutgoingStream(0)
        } else {
          largestIncomingStreamId = streamId
          streamStates.put(streamId, Idle)
          Idle
        }
      case state => state
    }

  def activeStreamCount(): Int = streamStates.size

  /** Called by Http2ServerDemux to let the state machine handle StreamFrameEvents */
  def handleStreamEvent(e: StreamFrameEvent): Unit =
    updateState(e.streamId, _.handle(e), "handleStreamEvent", e.frameTypeName)

  /** Called by Http2ServerDemux when a stream comes in from the user-handler */
  def handleOutgoingCreated(stream: Http2SubStream): Unit = {
    stream.initialHeaders.priorityInfo.foreach(multiplexer.updatePriority)
    if (streamFor(stream.streamId) != Closed) {
      multiplexer.pushControlFrame(stream.initialHeaders)

      if (stream.initialHeaders.endStream) {
        updateState(stream.streamId, _.handleOutgoingCreatedAndFinished(stream.correlationAttributes), "handleOutgoingCreatedAndFinished")
      } else {
        val outStream = OutStream(stream)
        updateState(stream.streamId, _.handleOutgoingCreated(outStream, stream.correlationAttributes), "handleOutgoingCreated")
      }
    } else
      // stream was cancelled by peer before our response was ready
      stream.data.foreach(_.runWith(Sink.cancelled)(subFusingMaterializer))

  }

  // Called by the outgoing stream multiplexer when that side of the stream is ended.
  def handleOutgoingEnded(streamId: Int): Unit =
    updateState(streamId, _.handleOutgoingEnded(), "handleOutgoingEnded")

  def handleOutgoingFailed(streamId: Int, cause: Throwable): Unit =
    updateState(streamId, _.handleOutgoingFailed(cause), "handleOutgoingFailed")

  /** Called by multiplexer to distribute changes from INITIAL_WINDOW_SIZE to all streams */
  def distributeWindowDeltaToAllStreams(delta: Int): Unit =
    updateAllStates({
      case s: Sending => s.increaseWindow(delta)
      case x          => x
    }, "distributeWindowDeltaToAllStreams")

  /** Called by the multiplexer if ready to send a data frame */
  def pullNextFrame(streamId: Int, maxSize: Int): PullFrameResult =
    updateStateAndReturn(streamId, _.pullNextFrame(maxSize), "pullNextFrame")

  /** Entry-point to handle IncomingStreamBuffer.onPull through the state machine */
  def incomingStreamPulled(streamId: Int): Unit =
    updateState(streamId, _.incomingStreamPulled(), "incomingStreamPulled")

  private def updateAllStates(handle: StreamState => StreamState, event: String, eventArg: AnyRef = null): Unit =
    streamStates.keys.foreach(streamId => updateState(streamId.toInt, handle, event, eventArg))

  private def updateState(streamId: Int, handle: StreamState => StreamState, event: String, eventArg: AnyRef = null): Unit =
    updateStateAndReturn(streamId, x => (handle(x), ()), event, eventArg)

  // Calling multiplexer.enqueueOutStream directly out of the state machine is not allowed, because it might try to
  // reenter the state machine with `pullNextState`. This call defers enqueuing until the current state machine operation
  // is done.
  private var deferredStreamToEnqueue: Int = -1
  private def enqueueOutStream(streamId: Int): Unit = if (stateMachineRunning) {
    require(deferredStreamToEnqueue == -1, "Only one stream can be enqueued during a single state change")
    deferredStreamToEnqueue = streamId
  } else
    multiplexer.enqueueOutStream(streamId)

  private var stateMachineRunning = false
  private def updateStateAndReturn[R](streamId: Int, handle: StreamState => (StreamState, R), event: String, eventArg: AnyRef = null): R = {
    require(!stateMachineRunning, "State machine already running")
    stateMachineRunning = true

    val oldState = streamFor(streamId)

    val (newState, ret) = handle(oldState)
    newState match {
      case Closed =>
        streamStates.remove(streamId)
        if (streamStates.isEmpty) onAllStreamsClosed()
        tryPullSubStreams()
      case newState => streamStates.put(streamId, newState)
    }

    debug(s"Incoming side of stream [$streamId] changed state: ${oldState.stateName} -> ${newState.stateName} after handling [$event${if (eventArg ne null) s"($eventArg)" else ""}]")

    stateMachineRunning = false
    if (deferredStreamToEnqueue != -1) {
      val streamId = deferredStreamToEnqueue
      deferredStreamToEnqueue = -1
      if (streamStates.contains(streamId))
        multiplexer.enqueueOutStream(streamId)
    }
    ret
  }

  /** Called to cleanup any state when the connection is torn down */
  def shutdownStreamHandling(): Unit = updateAllStates({ id => id.shutdown(); Closed }, "shutdownStreamHandling")

  def resetStream(streamId: Int, errorCode: ErrorCode): Unit = {
    updateState(streamId, _ => Closed, "resetStream") // force stream to be closed
    multiplexer.pushControlFrame(RstStreamFrame(streamId, errorCode))
  }

  /**
   * States roughly correspond to states as given in https://http2.github.io/http2-spec/#StreamStates.
   *
   * Substates were introduced to also track the state of our user-side
   *
   * States:
   *   * Idle                                     | server | client <- Idle, initial state, usually not tracked explicitly
   *   * OpenReceivingDataFirst                   | server          <- Open, got (request) HEADERS but didn't send (response) HEADERS yet
   *   * OpenSendingData                          |          client <- Open, sent (request) HEADERS but didn't receive (response) HEADERS yet
   *   * Open                                     | server | client <- Open, bidirectional, both sides sent HEADERS
   *   * HalfClosedLocalWaitingForPeerStream      |          client <- HalfClosedLocal, our stream side done, waiting for peer HEADERS
   *   * HalfClosedLocal                          | server | client <- HalfClosedLocal, all HEADERS sent, sent our endStream = true, receiving DATA from peer
   *   * HalfClosedRemoteWaitingForOutgoingStream | server          <- HalfClosedRemote, waiting for our HEADERS from user
   *   * HalfClosedRemoteSendingData              | server | client <- HalfClosedRemote, sent our HEADERS, now sending out DATA
   *   * Closed                                   | server | client <- Closed, final state, not tracked explicitly
   *
   * Server states:
   *   * Idle -> OpenReceivingDataFirst: on receiving request HEADERS with endStream = false
   *   * Idle -> HalfClosedRemoteWaitingForOutgoingStream: on receiving HEADERS with endStream = true
   *   * OpenReceivingDataFirst -> HalfClosedRemoteWaitingForOutgoingStream: after receiving endStream
   *   * OpenReceivingDataFirst -> Open: after user provided response before request was fully streamed in
   *   * HalfClosedRemoteWaitingForOutgoingStream -> HalfClosedRemoteSendingData: we sent response HEADERS with endStream = false
   *   * HalfClosedRemoteWaitingForOutgoingStream -> Closed: we sent response HEADERS with endStream = true
   *   * HalfClosedRemoteSendingData -> Closed: we sent DATA with endStream = true
   *   * Open -> HalfClosedRemoteSendingData: on receiving request DATA with endStream = true
   *   * Open -> HalfClosedLocal: on receiving response DATA with endStream = true before request has been fully received (uncommon)
   *   * HalfClosedLocal -> Closed: on receiving request DATA with endStream = true
   *
   * Client states:
   *   * Idle -> OpenSendingData: on sending out (request) HEADERS with endStream = false
   *   * Idle -> HalfClosedLocalWaitingForPeerStream: on sending out (request) HEADERS with endStream = true
   *   * OpenSendingData -> HalfClosedLocalWaitingForPeerStream: on sending out DATA with endStream = true
   *   * OpenSendingData -> Open: on receiving response HEADERS before request DATA was fully sent out
   *   * HalfClosedLocalWaitingForPeerStream -> HalfClosedLocal: on receiving response HEADERS with endStream = false
   *   * HalfClosedLocalWaitingForPeerStream -> Closed: on receiving response HEADERS with endStream = true
   *   * HalfClosedLocal -> Closed: on receiving response DATA with endStream = true
   *   * Open -> HalfClosedLocal: on sending out request DATA with endStream = true
   *   * Open -> HalfClosedRemoteSendingData: on receiving response DATA with endStream = true before request has been fully sent out (uncommon)
   *   * HalfClosedRemoteSendingData -> Closed: on sending out request DATA with endStream = true
   */
  sealed abstract class StreamState { self: Product =>
    def handle(event: StreamFrameEvent): StreamState

    def stateName: String = productPrefix

    /** Called when we receive a user-created stream (that is open for more data) */
    def handleOutgoingCreated(outStream: OutStream, correlationAttributes: Map[AttributeKey[_], _]): StreamState = {
      warning(s"handleOutgoingCreated received unexpectedly in state $stateName. This indicates a bug in Akka HTTP, please report it to the issue tracker.")
      this
    }
    /** Called when we receive a user-created stream that is already closed */
    def handleOutgoingCreatedAndFinished(correlationAttributes: Map[AttributeKey[_], _]): StreamState = {
      warning(s"handleOutgoingCreatedAndFinished received unexpectedly in state $stateName. This indicates a bug in Akka HTTP, please report it to the issue tracker.")
      this
    }
    def handleOutgoingEnded(): StreamState = {
      warning(s"handleOutgoingEnded received unexpectedly in state $stateName. This indicates a bug in Akka HTTP, please report it to the issue tracker.")
      this
    }
    def handleOutgoingFailed(cause: Throwable): StreamState = {
      warning(s"handleOutgoingFailed received unexpectedly in state $stateName. This indicates a bug in Akka HTTP, please report it to the issue tracker.")
      this
    }
    def receivedUnexpectedFrame(e: StreamFrameEvent): StreamState = {
      debug(s"Received unexpected frame of type ${e.frameTypeName} for stream ${e.streamId} in state $stateName")
      pushGOAWAY(ErrorCode.PROTOCOL_ERROR, s"Received unexpected frame of type ${e.frameTypeName} for stream ${e.streamId} in state $stateName")
      shutdown()
      Closed
    }

    protected def expectIncomingStream(
      event:                 StreamFrameEvent,
      nextStateEmpty:        StreamState,
      nextStateStream:       IncomingStreamBuffer => StreamState,
      correlationAttributes: Map[AttributeKey[_], _]             = Map.empty): StreamState =
      event match {
        case frame @ ParsedHeadersFrame(streamId, endStream, _, _) =>
          if (endStream) {
            dispatchSubstream(frame, Left(ByteString.empty), correlationAttributes)
            nextStateEmpty
          } else if (settings.minCollectStrictEntitySize > 0)
            CollectingIncomingData(frame, correlationAttributes, ByteString.empty, extraInitialWindow = 0)
          else
            dispatchStream(streamId, frame, ByteString.empty, correlationAttributes, nextStateStream)

        case x => receivedUnexpectedFrame(x)
      }

    protected def dispatchStream(
      streamId:              Int,
      headers:               ParsedHeadersFrame,
      initialData:           ByteString,
      correlationAttributes: Map[AttributeKey[_], _],
      nextStateStream:       IncomingStreamBuffer => StreamState): StreamState = {
      val subSource = new SubSourceOutlet[Any](s"substream-out-$streamId")
      val buffer = new IncomingStreamBuffer(streamId, subSource)
      if (initialData.nonEmpty) buffer.onDataFrame(DataFrame(streamId, endStream = false, initialData)) // fabricate frame
      dispatchSubstream(headers, Right(Source.fromGraph(subSource.source)), correlationAttributes)
      nextStateStream(buffer)
    }

    def pullNextFrame(maxSize: Int): (StreamState, PullFrameResult) = throw new IllegalStateException(s"pullNextFrame not supported in state $stateName")
    def incomingStreamPulled(): StreamState = throw new IllegalStateException(s"incomingStreamPulled not supported in state $stateName")

    /** Called to cleanup any state when the connection is torn down */
    def shutdown(): Unit = ()
  }

  case object Idle extends StreamState {
    def handle(event: StreamFrameEvent): StreamState =
      if (event.isInstanceOf[ParsedHeadersFrame] && activeStreamCount() > settings.maxConcurrentStreams) {
        // When trying to open a new Stream, if that op would exceed the maxConcurrentStreams, then refuse the op
        debug("Peer trying to open stream that would exceed `maxConcurrentStreams`, refusing stream")
        multiplexer.pushControlFrame(RstStreamFrame(event.streamId, ErrorCode.REFUSED_STREAM))
        Closed
      } else
        expectIncomingStream(event, HalfClosedRemoteWaitingForOutgoingStream(0), OpenReceivingDataFirst(_, 0))

    override def handleOutgoingCreated(outStream: OutStream, correlationAttributes: Map[AttributeKey[_], _]): StreamState = OpenSendingData(outStream, correlationAttributes)
    override def handleOutgoingCreatedAndFinished(correlationAttributes: Map[AttributeKey[_], _]): StreamState = HalfClosedLocalWaitingForPeerStream(correlationAttributes)
  }
  /** Special state that allows collecting some incoming data before dispatching it either as strict or streamed entity */
  case class CollectingIncomingData(
    headers:               ParsedHeadersFrame,
    correlationAttributes: Map[AttributeKey[_], _],
    collectedData:         ByteString,
    extraInitialWindow:    Int) extends ReceivingData {

    override protected def onDataFrame(dataFrame: DataFrame): StreamState = {
      val newData = collectedData ++ dataFrame.payload

      if (dataFrame.endStream) {
        totalBufferedData -= newData.length
        dispatchSubstream(headers, Left(newData), correlationAttributes)
        HalfClosedRemoteWaitingForOutgoingStream(extraInitialWindow)
      } else if (newData.length >= settings.minCollectStrictEntitySize)
        dispatchStream(dataFrame.streamId, headers, newData, correlationAttributes, OpenReceivingDataFirst(_, extraInitialWindow))
      else
        copy(collectedData = newData)
    }

    override protected def onTrailer(parsedHeadersFrame: ParsedHeadersFrame): StreamState = this // trailing headers not supported for requests right now
    override protected def incrementWindow(delta: Int): StreamState = copy(extraInitialWindow = extraInitialWindow + delta)
    override protected def onRstStreamFrame(rstStreamFrame: RstStreamFrame): Unit = {} // nothing to do here
  }
  case class OpenReceivingDataFirst(buffer: IncomingStreamBuffer, extraInitialWindow: Int = 0) extends ReceivingDataWithBuffer(HalfClosedRemoteWaitingForOutgoingStream(extraInitialWindow)) {
    override def handleOutgoingCreated(outStream: OutStream, correlationAttributes: Map[AttributeKey[_], _]): StreamState = {
      outStream.increaseWindow(extraInitialWindow)
      Open(buffer, outStream)
    }
    override def handleOutgoingCreatedAndFinished(correlationAttributes: Map[AttributeKey[_], _]): StreamState = HalfClosedLocal(buffer)
    override def handleOutgoingEnded(): StreamState = Closed

    override def incrementWindow(delta: Int): StreamState = copy(extraInitialWindow = extraInitialWindow + delta)
  }
  trait Sending extends StreamState { self: Product =>
    protected def outStream: OutStream

    override def pullNextFrame(maxSize: Int): (StreamState, PullFrameResult) = {
      val frame = outStream.nextFrame(maxSize)

      val res =
        outStream.endStreamIfPossible() match {
          case Some(trailer) =>
            PullFrameResult.SendFrameAndTrailer(frame, trailer)
          case None =>
            PullFrameResult.SendFrame(frame, outStream.canSend)
        }

      val nextState =
        if (outStream.isDone) this.handleOutgoingEnded()
        else this

      (nextState, res)
    }

    def handleWindowUpdate(windowUpdate: WindowUpdateFrame): StreamState = increaseWindow(windowUpdate.windowSizeIncrement)

    override def handleOutgoingFailed(cause: Throwable): StreamState = Closed

    abstract override def shutdown(): Unit = {
      outStream.cancelStream()
      super.shutdown()
    }

    def increaseWindow(delta: Int): StreamState = {
      outStream.increaseWindow(delta)
      this
    }
  }

  case class OpenSendingData(outStream: OutStream, correlationAttributes: Map[AttributeKey[_], _]) extends StreamState with Sending {
    override def handle(event: StreamFrameEvent): StreamState = event match {
      case _: ParsedHeadersFrame =>
        expectIncomingStream(event, HalfClosedRemoteSendingData(outStream), Open(_, outStream), correlationAttributes)
      case w: WindowUpdateFrame =>
        handleWindowUpdate(w)
      case r: RstStreamFrame =>
        outStream.cancelStream()
        Closed
      case _ =>
        outStream.cancelStream()
        receivedUnexpectedFrame(event)
    }

    override def handleOutgoingEnded(): StreamState = HalfClosedLocalWaitingForPeerStream(correlationAttributes)
  }
  case class HalfClosedLocalWaitingForPeerStream(correlationAttributes: Map[AttributeKey[_], _]) extends StreamState {
    override def handle(event: StreamFrameEvent): StreamState = event match {
      case _: WindowUpdateFrame =>
        // We're not planning on sending any data on this stream anymore, so we don't care about window updates.
        this
      case _ =>
        expectIncomingStream(event, Closed, HalfClosedLocal(_), correlationAttributes)
    }
  }
  sealed abstract class ReceivingData extends StreamState { self: Product =>
    def handle(event: StreamFrameEvent): StreamState = event match {
      case d: DataFrame =>
        outstandingConnectionLevelWindow -= d.sizeInWindow
        totalBufferedData += d.payload.length // padding can be seen as instantly discarded

        if (outstandingConnectionLevelWindow < 0) {
          shutdown()
          pushGOAWAY(ErrorCode.FLOW_CONTROL_ERROR, "Received more data than connection-level window would allow")
          Closed
        } else {
          val nextState = onDataFrame(d)

          val windowSizeIncrement = flowController.onConnectionDataReceived(outstandingConnectionLevelWindow, totalBufferedData)
          if (windowSizeIncrement > 0) {
            multiplexer.pushControlFrame(WindowUpdateFrame(Http2Protocol.NoStreamId, windowSizeIncrement))
            outstandingConnectionLevelWindow += windowSizeIncrement
          }
          nextState
        }
      case r: RstStreamFrame =>
        onRstStreamFrame(r)
        Closed

      case h: ParsedHeadersFrame =>
        onTrailer(h)

      case w: WindowUpdateFrame =>
        incrementWindow(w.windowSizeIncrement)

      case _ => receivedUnexpectedFrame(event)
    }
    protected def onDataFrame(dataFrame: DataFrame): StreamState
    protected def onTrailer(parsedHeadersFrame: ParsedHeadersFrame): StreamState
    protected def incrementWindow(delta: Int): StreamState
    protected def onRstStreamFrame(rstStreamFrame: RstStreamFrame): Unit
  }
  sealed abstract class ReceivingDataWithBuffer(afterEndStreamReceived: StreamState) extends ReceivingData { self: Product =>
    protected def buffer: IncomingStreamBuffer

    override protected def onDataFrame(dataFrame: DataFrame): StreamState = {
      buffer.onDataFrame(dataFrame)
      afterBufferEvent
    }
    override protected def onTrailer(parsedHeadersFrame: ParsedHeadersFrame): StreamState = {
      buffer.onTrailingHeaders(parsedHeadersFrame)
      afterBufferEvent
    }

    override protected def onRstStreamFrame(rstStreamFrame: RstStreamFrame): Unit = buffer.onRstStreamFrame(rstStreamFrame)

    override def incomingStreamPulled(): StreamState = {
      buffer.dispatchNextChunk()
      afterBufferEvent
    }

    override def shutdown(): Unit = {
      buffer.shutdown()
      super.shutdown()
    }

    def incrementWindow(delta: Int): StreamState

    def afterBufferEvent: StreamState = if (buffer.isDone) afterEndStreamReceived else this
  }

  // on the incoming side there's (almost) no difference between Open and HalfClosedLocal
  case class Open(buffer: IncomingStreamBuffer, outStream: OutStream) extends ReceivingDataWithBuffer(HalfClosedRemoteSendingData(outStream)) with Sending {
    override def handleOutgoingEnded(): StreamState = HalfClosedLocal(buffer)

    override protected def onRstStreamFrame(rstStreamFrame: RstStreamFrame): Unit = {
      super.onRstStreamFrame(rstStreamFrame)
      outStream.cancelStream()
    }
    override def incrementWindow(delta: Int): StreamState = {
      outStream.increaseWindow(delta)
      this
    }
  }
  /**
   * We have closed the outgoing stream, but the incoming stream is still going.
   */
  case class HalfClosedLocal(buffer: IncomingStreamBuffer) extends ReceivingDataWithBuffer(Closed) {
    override def incrementWindow(delta: Int): StreamState = this // no op, already finished sending
  }

  case class HalfClosedRemoteWaitingForOutgoingStream(extraInitialWindow: Int) extends StreamState {
    // FIXME: DRY with below
    override def handle(event: StreamFrameEvent): StreamState = event match {
      case r: RstStreamFrame    => Closed
      case w: WindowUpdateFrame => copy(extraInitialWindow = extraInitialWindow + w.windowSizeIncrement)
      case _                    => receivedUnexpectedFrame(event)
    }

    override def handleOutgoingCreated(outStream: OutStream, correlationAttributes: Map[AttributeKey[_], _]): StreamState = {
      outStream.increaseWindow(extraInitialWindow)
      HalfClosedRemoteSendingData(outStream)
    }
    override def handleOutgoingCreatedAndFinished(correlationAttributes: Map[AttributeKey[_], _]): StreamState = Closed
  }
  /**
   * They have closed the incoming stream, but the outgoing stream is still going.
   */
  case class HalfClosedRemoteSendingData(outStream: OutStream) extends StreamState with Sending {
    def handle(event: StreamFrameEvent): StreamState = event match {
      case r: RstStreamFrame =>
        outStream.cancelStream()
        Closed
      case w: WindowUpdateFrame => handleWindowUpdate(w)
      case _                    => receivedUnexpectedFrame(event)
    }

    override def handleOutgoingEnded(): StreamState = Closed
  }
  case object Closed extends StreamState {
    override def handleOutgoingEnded(): StreamState = this
    override def handleOutgoingFailed(cause: Throwable): StreamState = this

    def handle(event: StreamFrameEvent): StreamState = event match {
      // https://http2.github.io/http2-spec/#StreamStates
      // Endpoints MUST ignore WINDOW_UPDATE or RST_STREAM frames received in this state,
      case _: RstStreamFrame | _: WindowUpdateFrame | _: DataFrame =>
        // FIXME: do we need to adjust connection level window here as described in https://httpwg.org/specs/rfc7540.html#StreamStates:
        // > Flow-controlled frames (i.e., DATA) received after sending RST_STREAM are counted toward the connection
        // > flow-control window. Even though these frames might be ignored, because they are sent before the sender
        // > receives the RST_STREAM, the sender will consider the frames to count against the flow-control window.
        //
        // or should connection window adjustments be done in Http2ServerDemux directly?
        this
      case _ =>
        receivedUnexpectedFrame(event)
    }
  }

  class IncomingStreamBuffer(streamId: Int, outlet: SubSourceOutlet[Any]) extends OutHandler {
    private var buffer: ByteString = ByteString.empty
    private var trailingHeaders: Option[HttpEntity.ChunkStreamPart] = None
    private var wasClosed: Boolean = false
    private var outstandingStreamWindow: Int = Http2Protocol.InitialWindowSize // adapt if we negotiate greater sizes by settings
    outlet.setHandler(this)

    def onPull(): Unit = incomingStreamPulled(streamId)
    override def onDownstreamFinish(): Unit = {
      debug(s"Incoming side of stream [$streamId]: cancelling because downstream finished")
      multiplexer.pushControlFrame(RstStreamFrame(streamId, ErrorCode.CANCEL))
      // FIXME: go through state machine and don't manipulate vars directly here
      streamStates.remove(streamId)
      wasClosed = true
      buffer = ByteString.empty
      trailingHeaders = None
    }

    def isDone: Boolean = outlet.isClosed

    def onDataFrame(data: DataFrame): Unit =
      if (wasClosed) {
        shutdown()
        pushGOAWAY(ErrorCode.PROTOCOL_ERROR, s"Received unexpected DATA frame after stream was already (half-)closed")
      } else {
        wasClosed = data.endStream

        outstandingStreamWindow -= data.sizeInWindow
        if (outstandingStreamWindow < 0) {
          shutdown()
          multiplexer.pushControlFrame(RstStreamFrame(streamId, ErrorCode.FLOW_CONTROL_ERROR))
          // also close response delivery if that has already started
          multiplexer.closeStream(streamId)
        } else {
          buffer ++= data.payload
          debug(s"Received DATA ${data.sizeInWindow} for stream [$streamId], remaining window space now $outstandingStreamWindow, buffered: ${buffer.length}")
          dispatchNextChunk()
        }
      }
    def onTrailingHeaders(headers: ParsedHeadersFrame): Unit = {
      trailingHeaders = wrapTrailingHeaders(headers)
      if (headers.endStream)
        // simulate end stream by empty dataframe
        onDataFrame(DataFrame(headers.streamId, endStream = true, ByteString.empty))
      else
        pushGOAWAY(Http2Protocol.ErrorCode.PROTOCOL_ERROR, "Got unexpected mid-stream HEADERS frame")
    }
    def onRstStreamFrame(rst: RstStreamFrame): Unit = {
      outlet.fail(new PeerClosedStreamException(rst.streamId, rst.errorCode))
      buffer = ByteString.empty
      trailingHeaders = None
      wasClosed = true
    }

    def dispatchNextChunk(): Unit = {
      if (buffer.nonEmpty && outlet.isAvailable) {
        val dataSize = buffer.length min settings.requestEntityChunkSize
        outlet.push(buffer.take(dataSize))
        buffer = buffer.drop(dataSize)

        totalBufferedData -= dataSize

        debug(s"Dispatched chunk of $dataSize for stream [$streamId], remaining window space now $outstandingStreamWindow, buffered: ${buffer.length}")
        updateWindows()
      }
      if (buffer.isEmpty && wasClosed) {
        trailingHeaders match {
          case Some(trailer) =>
            if (outlet.isAvailable) {
              outlet.push(trailer)
              trailingHeaders = None
              outlet.complete()
            }
          case None =>
            outlet.complete()
        }

      }
    }

    private def updateWindows(): Unit = {
      val IncomingFlowController.WindowIncrements(connectionLevel, streamLevel) = flowController.onStreamDataDispatched(
        outstandingConnectionLevelWindow, totalBufferedData,
        outstandingStreamWindow, buffer.length)

      if (connectionLevel > 0) {
        multiplexer.pushControlFrame(WindowUpdateFrame(Http2Protocol.NoStreamId, connectionLevel))
        outstandingConnectionLevelWindow += connectionLevel
      }
      if (streamLevel > 0 && !wasClosed /* No reason to update window on closed streams */ ) {
        multiplexer.pushControlFrame(WindowUpdateFrame(streamId, streamLevel))
        outstandingStreamWindow += streamLevel
      }

      debug(
        s"adjusting con-level window by $connectionLevel, stream-level window by $streamLevel, " +
          s"remaining window space now $outstandingStreamWindow, buffered: ${buffer.length}, " +
          s"remaining connection window space now $outstandingConnectionLevelWindow, total buffered: $totalBufferedData")
    }

    def shutdown(): Unit =
      if (!outlet.isClosed) outlet.fail(Http2StreamHandling.ConnectionWasAbortedException)
  }

  trait OutStream {
    def canSend: Boolean
    def cancelStream(): Unit
    def endStreamIfPossible(): Option[FrameEvent]
    def nextFrame(maxBytesToSend: Int): DataFrame
    def increaseWindow(delta: Int): Unit
    def isDone: Boolean
  }
  object OutStream {
    def apply(sub: Http2SubStream): OutStream = {
      val info = new OutStreamImpl(sub.streamId, OptionVal.None, multiplexer.currentInitialWindow, sub.trailingHeaders)
      sub.data match {
        case Right(data) =>
          val subIn = new SubSinkInlet[Any](s"substream-in-${sub.streamId}")
          info.registerIncomingData(subIn)
          data.runWith(subIn.sink)(subFusingMaterializer)
        case Left(data) =>
          info.addAllData(data)
      }
      info
    }
  }
  final class OutStreamImpl(
    val streamId:           Int,
    private var maybeInlet: OptionVal[SubSinkInlet[_]],
    var outboundWindowLeft: Int,
    var trailer:            OptionVal[ParsedHeadersFrame]
  ) extends InHandler with OutStream {
    private def inlet: SubSinkInlet[_] = maybeInlet.get

    private var buffer: ByteString = ByteString.empty
    private var upstreamClosed: Boolean = false
    private var isEnqueued: Boolean = false
    var endStreamSent: Boolean = false

    /** Designates whether nextFrame can be called to get the next frame. */
    def canSend: Boolean = buffer.nonEmpty && outboundWindowLeft > 0
    def isDone: Boolean = endStreamSent

    def enqueueIfPossible(): Unit =
      if (canSend && !isEnqueued) {
        isEnqueued = true
        enqueueOutStream(streamId)
      }

    def registerIncomingData(inlet: SubSinkInlet[_]): Unit = {
      require(!maybeInlet.isDefined)

      this.maybeInlet = OptionVal.Some(inlet)
      inlet.pull()
      inlet.setHandler(this)
    }
    def addAllData(data: ByteString): Unit = {
      require(buffer.isEmpty)
      buffer = data
      upstreamClosed = true
      enqueueIfPossible()
    }

    def nextFrame(maxBytesToSend: Int): DataFrame = {
      val toTake = maxBytesToSend min buffer.length min outboundWindowLeft
      val toSend = buffer.take(toTake)
      require(toSend.nonEmpty)

      outboundWindowLeft -= toTake
      buffer = buffer.drop(toTake)

      val endStream = upstreamClosed && buffer.isEmpty && trailer.isEmpty
      if (endStream) {
        endStreamSent = true
      } else
        maybePull()

      debug(s"[$streamId] sending ${toSend.length} bytes, endStream = $endStream, remaining buffer [${buffer.length}], remaining stream-level WINDOW [$outboundWindowLeft]")

      // Multiplexer will enqueue for us if we report more data being available
      // We cannot call `multiplexer.enqueueOutStream` from here because this is called from the multiplexer.
      isEnqueued = !isDone && canSend

      DataFrame(streamId, endStream, toSend)
    }

    private def readyToSendFinalFrame: Boolean = upstreamClosed && !endStreamSent && buffer.isEmpty

    def endStreamIfPossible(): Option[FrameEvent] =
      if (readyToSendFinalFrame) {
        val finalFrame = trailer.getOrElse(DataFrame(streamId, endStream = true, ByteString.empty))
        endStreamSent = true
        Some(finalFrame)
      } else
        None

    private def maybePull(): Unit =
      // TODO: Check that buffer is not too much over the limit (which we might warn the user about)
      //       The problem here is that backpressure will only work properly if batch elements like
      //       ByteString have a reasonable size.
      if (!upstreamClosed && buffer.length < multiplexer.maxBytesToBufferPerSubstream && !inlet.hasBeenPulled && !inlet.isClosed) inlet.pull()

    /** Cleans up internal state (but not external) */
    private def cleanupStream(): Unit = {
      buffer = ByteString.empty
      upstreamClosed = true
      endStreamSent = true
      maybeInlet match {
        case OptionVal.Some(inlet) => inlet.cancel()
        case OptionVal.None        => // nothing to clean up
      }
    }

    def cancelStream(): Unit = {
      cleanupStream()
      if (isEnqueued) multiplexer.closeStream(streamId)
    }
    def bufferedBytes: Int = buffer.length

    override def increaseWindow(increment: Int): Unit = if (increment >= 0) {
      outboundWindowLeft += increment
      debug(s"Updating window for $streamId by $increment to $outboundWindowLeft buffered bytes: $bufferedBytes")
      enqueueIfPossible()
    }

    // external callbacks, need to make sure that potential stream state changing events are run through the state machine
    override def onPush(): Unit = {
      inlet.grab() match {
        case newData: ByteString          => buffer ++= newData
        case HttpEntity.Chunk(newData, _) => buffer ++= newData
        case HttpEntity.LastChunk(_, headers) =>
          if (headers.nonEmpty && !trailer.isEmpty)
            log.warning("Found both an attribute with trailing headers, and headers in the `LastChunk`. This is not supported.")
          trailer = OptionVal.Some(ParsedHeadersFrame(streamId, endStream = true, HttpMessageRendering.renderHeaders(headers, log, isServer, shouldRenderAutoHeaders = false, dateHeaderRendering = DateHeaderRendering.Unavailable), None))
      }

      maybePull()

      // else wait for more data being drained
      enqueueIfPossible() // multiplexer might call pullNextFrame which goes through the state machine => OK
    }

    override def onUpstreamFinish(): Unit = {
      upstreamClosed = true

      endStreamIfPossible().foreach { frame =>
        multiplexer.pushControlFrame(frame)
        // also notify state machine that stream is done
        handleOutgoingEnded(streamId)
        cleanupStream()
      }
    }
    override def onUpstreamFailure(ex: Throwable): Unit = {
      log.error(ex, s"Substream $streamId failed with $ex")
      multiplexer.pushControlFrame(RstStreamFrame(streamId, Http2Protocol.ErrorCode.INTERNAL_ERROR))
      handleOutgoingFailed(streamId, ex)
      cleanupStream()
    }
  }
  // needed once PUSH_PROMISE support was added
  //case object ReservedLocal extends IncomingStreamState
  //case object ReservedRemote extends IncomingStreamState
}
private[http2] object Http2StreamHandling {
  val ConnectionWasAbortedException = new IllegalStateException("The HTTP/2 connection was shut down while the request was still ongoing") with NoStackTrace
}
