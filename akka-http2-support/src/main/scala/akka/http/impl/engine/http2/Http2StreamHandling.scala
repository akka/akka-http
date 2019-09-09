/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.annotation.InternalApi
import akka.http.impl.engine.http2.Http2Protocol.ErrorCode
import akka.http.scaladsl.model.http2.PeerClosedStreamException
import akka.http.scaladsl.settings.Http2ServerSettings
import akka.stream.scaladsl.Source
import akka.stream.stage.{ GraphStageLogic, OutHandler, StageLogging }
import akka.util.ByteString

import scala.collection.immutable

import FrameEvent._

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
  def settings: Http2ServerSettings
  def pushGOAWAY(errorCode: ErrorCode, debug: String): Unit
  def dispatchSubstream(sub: Http2SubStream): Unit

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
        else {
          largestIncomingStreamId = streamId
          incomingStreams += streamId -> Idle
          Idle
        }
    }
  def handleStreamEvent(e: StreamFrameEvent): Unit = {
    updateState(e.streamId, _.handle(e))
  }
  // Called by the outgoing stream multiplexer when that side of the stream is ended.
  def handleOutgoingEnded(streamId: Int): Unit = {
    updateState(streamId, _.handleOutgoingEnded())
  }
  private def updateState(streamId: Int, handle: IncomingStreamState => IncomingStreamState): Unit = {
    handle(streamFor(streamId)) match {
      case Closed   => incomingStreams -= streamId
      case newState => incomingStreams += streamId -> newState
    }
  }
  def resetStream(streamId: Int, errorCode: ErrorCode): Unit = {
    incomingStreams -= streamId
    multiplexer.pushControlFrame(RstStreamFrame(streamId, errorCode))
  }

  /**
   * https://http2.github.io/http2-spec/#StreamStates
   */
  sealed abstract class IncomingStreamState { _: Product =>
    def handle(event: StreamFrameEvent): IncomingStreamState

    def stateName: String = productPrefix
    def handleOutgoingEnded(): IncomingStreamState = {
      log.warning(s"handleOutgoingEnded received unexpectedly in state $stateName. This indicates a bug in Akka HTTP, please report it to the issue tracker.")
      this
    }
    def receivedUnexpectedFrame(e: StreamFrameEvent): IncomingStreamState = {
      pushGOAWAY(ErrorCode.PROTOCOL_ERROR, s"Received unexpected frame of type ${e.frameTypeName} for stream ${e.streamId} in state $stateName")
      Closed
    }
  }
  case object Idle extends IncomingStreamState {
    def handle(event: StreamFrameEvent): IncomingStreamState = event match {
      case frame @ ParsedHeadersFrame(streamId, endStream, headers, prioInfo) =>
        val (data, nextState) =
          if (endStream)
            (Source.empty, HalfClosedRemote)
          else {
            val subSource = new SubSourceOutlet[ByteString](s"substream-out-$streamId")
            (Source.fromGraph(subSource.source), Open(new IncomingStreamBuffer(streamId, subSource)))
          }

        // FIXME: after multiplexer PR is merged
        // prioInfo.foreach(multiplexer.updatePriority)
        dispatchSubstream(ByteHttp2SubStream(frame, data))
        nextState

      case x => receivedUnexpectedFrame(x)
    }
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
        multiplexer.cancelSubStream(r.streamId)
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

    protected def maybeFinishStream(endStream: Boolean): IncomingStreamState =
      if (endStream) afterEndStreamReceived else this
  }

  // on the incoming side there's (almost) no difference between Open and HalfClosedLocal
  case class Open(buffer: IncomingStreamBuffer) extends ReceivingData(HalfClosedRemote) {
    override def handleOutgoingEnded(): IncomingStreamState = HalfClosedLocal(buffer)
  }
  /**
   * We have closed the outgoing stream, but the incoming stream is still going.
   */
  case class HalfClosedLocal(buffer: IncomingStreamBuffer) extends ReceivingData(Closed)

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
    override def handleOutgoingEnded(): IncomingStreamState = Closed
  }
  case object Closed extends IncomingStreamState {
    def handle(event: StreamFrameEvent): IncomingStreamState = event match {
      // https://http2.github.io/http2-spec/#StreamStates
      // Endpoints MUST ignore WINDOW_UPDATE or RST_STREAM frames received in this state,
      case _: RstStreamFrame | _: WindowUpdateFrame =>
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
  }

  // needed once PUSH_PROMISE support was added
  //case object ReservedLocal extends IncomingStreamState
  //case object ReservedRemote extends IncomingStreamState
}
