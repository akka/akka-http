/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.annotation.InternalApi
import akka.http.impl.engine.http2.FrameEvent._
import akka.http.impl.engine.http2.Http2Protocol.ErrorCode
import akka.http.scaladsl.model.{ AttributeKey, HttpEntity }
import akka.http.scaladsl.model.http2.PeerClosedStreamException
import akka.http.scaladsl.settings.Http2CommonSettings
import akka.macros.LogHelper
import akka.stream.scaladsl.{ Sink, Source }
import akka.stream.stage.{ GraphStageLogic, InHandler, OutHandler }
import akka.util.ByteString

import scala.collection.immutable
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
private[http2] trait Http2StreamHandling { self: GraphStageLogic with LogHelper =>
  // required API from demux
  def isServer: Boolean
  def multiplexer: Http2Multiplexer
  def settings: Http2CommonSettings
  def pushGOAWAY(errorCode: ErrorCode, debug: String): Unit
  def dispatchSubstream(initialHeaders: ParsedHeadersFrame, data: Source[ByteString, Any], correlationAttributes: Map[AttributeKey[_], _]): Unit
  def isUpgraded: Boolean

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

  private var streamStates = new immutable.TreeMap[Int, StreamState]
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
    streamStates.size < maxConcurrentStreams
  }

  private def streamFor(streamId: Int): StreamState =
    streamStates.get(streamId) match {
      case Some(state) => state
      case None =>
        if (streamId <= largestIncomingStreamId) Closed // closed streams are never put into the map
        else if (isUpgraded && streamId == 1) {
          require(isServer)
          // Stream 1 is implicitly "half-closed" from the client toward the server (see Section 5.1), since the request is completed as an HTTP/1.1 request
          // https://http2.github.io/http2-spec/#discover-http
          largestIncomingStreamId = streamId
          streamStates += streamId -> HalfClosedRemoteWaitingForOutgoingStream(0)
          HalfClosedRemoteWaitingForOutgoingStream(0)
        } else {
          largestIncomingStreamId = streamId
          streamStates += streamId -> Idle // FIXME: is that needed at all? Should move out of Idle immediately
          Idle
        }
    }

  /** Called by Http2ServerDemux to let the state machine handle StreamFrameEvents */
  def handleStreamEvent(e: StreamFrameEvent): Unit =
    updateState(e.streamId, _.handle(e))

  /** Called by Http2ServerDemux when a stream comes in from the user-handler */
  def handleOutgoingCreated(stream: Http2SubStream): Unit = {
    stream.initialHeaders.priorityInfo.foreach(multiplexer.updatePriority)
    if (streamFor(stream.streamId) != Closed) {
      multiplexer.pushControlFrame(stream.initialHeaders)

      if (stream.initialHeaders.endStream) {
        updateState(stream.streamId, _.handleOutgoingCreatedAndFinished(stream.correlationAttributes))
      } else {
        val outStream = OutStream(stream)
        updateState(stream.streamId, _.handleOutgoingCreated(outStream, stream.correlationAttributes))
      }
    } else
      // stream was cancelled by peer before our response was ready
      stream.data.runWith(Sink.cancelled)(subFusingMaterializer)

  }

  // Called by the outgoing stream multiplexer when that side of the stream is ended.
  def handleOutgoingEnded(streamId: Int): Unit =
    updateState(streamId, _.handleOutgoingEnded())

  def handleOutgoingFailed(streamId: Int, cause: Throwable): Unit =
    updateState(streamId, _.handleOutgoingFailed(cause))

  /** Called by multiplexer to distribute changes from INITIAL_WINDOW_SIZE to all streams */
  def distributeWindowDeltaToAllStreams(delta: Int): Unit =
    updateAllStates {
      case s: Sending => s.increaseWindow(delta)
      case x          => x
    }

  /** Called by the multiplexer if ready to send a data frame */
  def pullNextFrame(streamId: Int, maxSize: Int): PullFrameResult =
    updateStateAndReturn(streamId, _.pullNextFrame(maxSize))

  private def updateAllStates(handle: StreamState => StreamState): Unit =
    streamStates.keys.foreach(updateState(_, handle))

  private def updateState(streamId: Int, handle: StreamState => StreamState): Unit =
    updateStateAndReturn(streamId, x => (handle(x), ()))

  private def updateStateAndReturn[T](streamId: Int, handle: StreamState => (StreamState, T)): T = {
    val oldState = streamFor(streamId)

    val (newState, ret) = handle(oldState)
    newState match {
      case Closed =>
        streamStates -= streamId
        tryPullSubStreams()
      case newState => streamStates += streamId -> newState
    }

    debug(s"Incoming side of stream [$streamId] changed state: ${oldState.stateName} -> ${newState.stateName} after running ${handle.getClass}")
    ret
  }
  /** Called to cleanup any state when the connection is torn down */
  def shutdownStreamHandling(): Unit = streamStates.keys.foreach(id => updateState(id, { x => x.shutdown(); Closed }))
  def resetStream(streamId: Int, errorCode: ErrorCode): Unit = {
    updateState(streamId, _ => Closed) // force stream to be closed
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
  sealed abstract class StreamState { _: Product =>
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
      Closed
    }

    protected def expectIncomingStream(
      event:                 StreamFrameEvent,
      nextStateEmpty:        StreamState,
      nextStateStream:       IncomingStreamBuffer => StreamState,
      correlationAttributes: Map[AttributeKey[_], _]             = Map.empty): StreamState =
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
          dispatchSubstream(frame, data, correlationAttributes)
          nextState

        case x => receivedUnexpectedFrame(x)
      }

    def pullNextFrame(maxSize: Int): (StreamState, PullFrameResult) = throw new IllegalStateException(s"pullNextFrame not supported in state $stateName")

    /** Called to cleanup any state when the connection is torn down */
    def shutdown(): Unit = ()
  }

  case object Idle extends StreamState {
    def handle(event: StreamFrameEvent): StreamState =
      if (event.isInstanceOf[ParsedHeadersFrame] && streamStates.size > settings.maxConcurrentStreams) {
        // When trying to open a new Stream, if that op would exceed the maxConcurrentStreams, then refuse the op
        debug("Peer trying to open stream that would exceed `maxConcurrentStreams`, refusing stream")
        multiplexer.pushControlFrame(RstStreamFrame(event.streamId, ErrorCode.REFUSED_STREAM))
        Closed
      } else
        expectIncomingStream(event, HalfClosedRemoteWaitingForOutgoingStream(0), OpenReceivingDataFirst(_, 0))

    override def handleOutgoingCreated(outStream: OutStream, correlationAttributes: Map[AttributeKey[_], _]): StreamState = OpenSendingData(outStream, correlationAttributes)
    override def handleOutgoingCreatedAndFinished(correlationAttributes: Map[AttributeKey[_], _]): StreamState = HalfClosedLocalWaitingForPeerStream(correlationAttributes)
  }
  case class OpenReceivingDataFirst(buffer: IncomingStreamBuffer, extraInitialWindow: Int = 0) extends ReceivingData(HalfClosedRemoteWaitingForOutgoingStream(extraInitialWindow)) {
    override def handleOutgoingCreated(outStream: OutStream, correlationAttributes: Map[AttributeKey[_], _]): StreamState = {
      outStream.increaseWindow(extraInitialWindow)
      Open(buffer, outStream)
    }
    override def handleOutgoingCreatedAndFinished(correlationAttributes: Map[AttributeKey[_], _]): StreamState = HalfClosedLocal(buffer)
    override def handleOutgoingEnded(): StreamState = Closed

    override protected def onReset(streamId: Int): Unit = multiplexer.closeStream(streamId)

    override def incrementWindow(delta: Int): StreamState = copy(extraInitialWindow = extraInitialWindow + delta)
  }
  trait Sending extends StreamState { _: Product =>
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
        if (outStream.isDone) handleOutgoingEnded()
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
      case _: ParsedHeadersFrame => expectIncomingStream(event, HalfClosedRemoteSendingData(outStream), Open(_, outStream), correlationAttributes)
      case w: WindowUpdateFrame  => handleWindowUpdate(w)
      case _                     => receivedUnexpectedFrame(event)
    }

    override def handleOutgoingEnded(): StreamState = HalfClosedLocalWaitingForPeerStream(correlationAttributes)
  }
  case class HalfClosedLocalWaitingForPeerStream(correlationAttributes: Map[AttributeKey[_], _]) extends StreamState {
    override def handle(event: StreamFrameEvent): StreamState = expectIncomingStream(event, Closed, HalfClosedLocal, correlationAttributes)
  }
  sealed abstract class ReceivingData(afterEndStreamReceived: StreamState) extends StreamState { _: Product =>
    protected def buffer: IncomingStreamBuffer
    def handle(event: StreamFrameEvent): StreamState = event match {
      case d: DataFrame =>
        outstandingConnectionLevelWindow -= d.sizeInWindow
        totalBufferedData += d.payload.size // padding can be seen as instantly discarded

        if (outstandingConnectionLevelWindow < 0) {
          buffer.shutdown()
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
        buffer.onTrailingHeaders(h.keyValuePairs)

        if (h.endStream) {
          buffer.onDataFrame(DataFrame(h.streamId, endStream = true, ByteString.empty)) // simulate end stream by empty dataframe
          debug(s"Ignored trailing HEADERS frame: $h")
        } else pushGOAWAY(Http2Protocol.ErrorCode.PROTOCOL_ERROR, "Got unexpected mid-stream HEADERS frame")

        maybeFinishStream(h.endStream)

      case w: WindowUpdateFrame =>
        incrementWindow(w.windowSizeIncrement)

      case _ => receivedUnexpectedFrame(event)
    }
    protected def onReset(streamId: Int): Unit

    protected def maybeFinishStream(endStream: Boolean): StreamState =
      if (endStream) afterEndStreamReceived else this

    override def shutdown(): Unit = {
      buffer.shutdown()
      super.shutdown()
    }

    def incrementWindow(delta: Int): StreamState
  }

  // on the incoming side there's (almost) no difference between Open and HalfClosedLocal
  case class Open(buffer: IncomingStreamBuffer, outStream: OutStream) extends ReceivingData(HalfClosedRemoteSendingData(outStream)) with Sending {
    override def handleOutgoingEnded(): StreamState = HalfClosedLocal(buffer)

    override protected def onReset(streamId: Int): Unit = {
      outStream.cancelStream()
      multiplexer.closeStream(streamId)
    }

    override def incrementWindow(delta: Int): StreamState = {
      outStream.increaseWindow(delta)
      this
    }
  }
  /**
   * We have closed the outgoing stream, but the incoming stream is still going.
   */
  case class HalfClosedLocal(buffer: IncomingStreamBuffer) extends ReceivingData(Closed) {
    override protected def onReset(streamId: Int): Unit = {
      // nothing further to do as we're already half-closed
    }

    override def incrementWindow(delta: Int): StreamState = this // no op, already finished sending
  }

  case class HalfClosedRemoteWaitingForOutgoingStream(extraInitialWindow: Int) extends StreamState {
    // FIXME: DRY with below
    override def handle(event: StreamFrameEvent): StreamState = event match {
      case r: RstStreamFrame =>
        multiplexer.closeStream(r.streamId)
        Closed
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
        multiplexer.closeStream(r.streamId)
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

  class IncomingStreamBuffer(streamId: Int, outlet: SubSourceOutlet[ByteString]) extends OutHandler {
    private var buffer: ByteString = ByteString.empty
    private var wasClosed: Boolean = false
    private var outstandingStreamWindow: Int = Http2Protocol.InitialWindowSize // adapt if we negotiate greater sizes by settings
    outlet.setHandler(this)

    def onPull(): Unit = dispatchNextChunk()
    override def onDownstreamFinish(): Unit = {
      debug(s"Incoming side of stream [$streamId]: cancelling because downstream finished")
      multiplexer.pushControlFrame(RstStreamFrame(streamId, ErrorCode.CANCEL))
      // FIXME: go through state machine and don't manipulate vars directly here
      streamStates -= streamId
    }

    def onDataFrame(data: DataFrame): Option[StreamState] = {
      if (data.endStream) wasClosed = true

      outstandingStreamWindow -= data.sizeInWindow
      if (outstandingStreamWindow < 0) {
        shutdown()
        multiplexer.pushControlFrame(RstStreamFrame(streamId, ErrorCode.FLOW_CONTROL_ERROR))
        // also close response delivery if that has already started
        multiplexer.closeStream(streamId)
        Some(Closed)
      } else {
        buffer ++= data.payload
        debug(s"Received DATA ${data.sizeInWindow} for stream [$streamId], remaining window space now $outstandingStreamWindow, buffered: ${buffer.size}")
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

        debug(s"Dispatched chunk of $dataSize for stream [$streamId], remaining window space now $outstandingStreamWindow, buffered: ${buffer.size}")
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

      debug(
        s"adjusting con-level window by $connectionLevel, stream-level window by $streamLevel, " +
          s"remaining window space now $outstandingStreamWindow, buffered: ${buffer.size}, " +
          s"remaining connection window space now $outstandingConnectionLevelWindow, total buffered: $totalBufferedData")
    }

    def shutdown(): Unit = outlet.fail(Http2StreamHandling.ConnectionWasAbortedException)
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
      val subIn = new SubSinkInlet[Any](s"substream-in-${sub.streamId}")
      val info = new OutStreamImpl(sub.streamId, None, multiplexer.currentInitialWindow)
      info.registerIncomingData(subIn)
      sub.data.runWith(subIn.sink)(subFusingMaterializer)
      info
    }
  }
  class OutStreamImpl(
    val streamId:           Int,
    private var maybeInlet: Option[SubSinkInlet[_]],
    var outboundWindowLeft: Int
  ) extends InHandler with OutStream {
    private def inlet: SubSinkInlet[_] = maybeInlet.get

    private var buffer: ByteString = ByteString.empty
    private var upstreamClosed: Boolean = false
    var endStreamSent: Boolean = false
    private var trailer: Option[ParsedHeadersFrame] = None

    /** Designates whether nextFrame can be called to get the next frame. */
    def canSend: Boolean = buffer.nonEmpty && outboundWindowLeft > 0
    def isDone: Boolean = endStreamSent

    def registerIncomingData(inlet: SubSinkInlet[_]): Unit = {
      require(!maybeInlet.isDefined)

      this.maybeInlet = Some(inlet)
      inlet.pull()
      inlet.setHandler(this)
    }

    def nextFrame(maxBytesToSend: Int): DataFrame = {
      val toTake = maxBytesToSend min buffer.size min outboundWindowLeft
      val toSend = buffer.take(toTake)
      require(toSend.nonEmpty)

      outboundWindowLeft -= toTake
      buffer = buffer.drop(toTake)

      val endStream = upstreamClosed && buffer.isEmpty && trailer.isEmpty
      if (endStream) {
        endStreamSent = true
      } else
        maybePull()

      debug(s"[$streamId] sending ${toSend.size} bytes, endStream = $endStream, remaining buffer [${buffer.size}], remaining stream-level WINDOW [$outboundWindowLeft]")

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

    private def maybePull(): Unit = {
      // TODO: Check that buffer is not too much over the limit (which we might warn the user about)
      //       The problem here is that backpressure will only work properly if batch elements like
      //       ByteString have a reasonable size.
      if (buffer.size < multiplexer.maxBytesToBufferPerSubstream && !inlet.hasBeenPulled && !inlet.isClosed) inlet.pull()
    }

    /** Cleans up internal state (but not external) */
    private def cleanupStream(): Unit = {
      buffer = ByteString.empty
      upstreamClosed = true
      endStreamSent = true
      maybeInlet.foreach(_.cancel())
    }

    def cancelStream(): Unit = cleanupStream()
    def bufferedBytes: Int = buffer.size

    override def increaseWindow(increment: Int): Unit = {
      outboundWindowLeft += increment
      debug(s"Updating window for $streamId by $increment to $outboundWindowLeft buffered bytes: $bufferedBytes")
      if (canSend) multiplexer.enqueueOutStream(streamId)
    }

    // external callbacks, need to make sure that potential stream state changing events are run through the state machine
    override def onPush(): Unit = {
      inlet.grab() match {
        case newData: ByteString          => buffer ++= newData
        case HttpEntity.Chunk(newData, _) => buffer ++= newData
        case HttpEntity.LastChunk(_, headers) =>
          trailer = Some(ParsedHeadersFrame(streamId, endStream = true, ResponseRendering.renderHeaders(headers, log, isServer), None))
      }

      maybePull()

      // else wait for more data being drained
      if (canSend) multiplexer.enqueueOutStream(streamId) // multiplexer might call pullNextFrame which goes through the state machine => OK
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
