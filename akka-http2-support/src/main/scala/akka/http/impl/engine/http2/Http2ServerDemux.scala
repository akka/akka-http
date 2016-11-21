/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.http.impl.engine.http2.Http2Compliance.IllegalHttp2FrameSize
import akka.http.impl.engine.http2.Http2Protocol.ErrorCode
import akka.stream.Attributes
import akka.stream.BidiShape
import akka.stream.Inlet
import akka.stream.Outlet
import akka.stream.scaladsl.Source
import akka.stream.stage.GraphStage
import akka.stream.stage.GraphStageLogic
import akka.stream.stage.InHandler

import scala.collection.mutable
import scala.concurrent.duration._
import scala.util.control.NonFatal

/**
 * This stage contains all control logic for handling frames and (de)muxing data to/from substreams.
 *
 * (This is not a final documentation, more like a brain-dump of how it could work.)
 *
 * The BidiStage consumes and produces FrameEvents from the network. It will output one Http2SubStream
 * for incoming frames per substream and likewise accepts a single Http2SubStream per substream with
 * outgoing frames.
 *
 * (An alternative API would just push a BidiHttp2SubStream(subStreamFlow: Flow[StreamFrameEvent, StreamFrameEvent])
 *  similarly to IncomingConnection. This would more accurately model the one-to-one relation between incoming and
 *  outgoing Http2Substream directions but wouldn't stack so nicely with other BidiFlows.)
 *
 * Backpressure logic:
 *
 *  * read all incoming frames without applying backpressure
 *    * this ensures that all "control" frames are read in a timely manner
 *    * though, make sure limits are not exceeded
 *      * max connection limit (which limits number of parallel requests)
 *      * window sizes for incoming data frames
 *    * that means we need to buffer incoming substream data until the user handler (consuming the source in the Http2SubStream)
 *      will read it
 *    * per-connection and per-stream window updates should reflect how much data was (not) yet passed
 *      into the user handler and therefore are the main backpressure mechanism towards the peer
 *  * for the outgoing frame side we need to decide which frames to send per incoming demand
 *    * control frames (settings, ping, acks, window updates etc.) -> responses to incoming frames
 *    * substream frames -> incoming frame data from substreams
 *    * to be able to make a decision some data must already be buffered for those two sources of incoming frames
 *
 * Demultiplexing:
 *  * distribute incoming frames to their respective targets:
 *    * control frames: handled internally, may generate outgoing control frames directly
 *    * incoming HEADERS frames: creates a new Http2SubStream including a SubSource that will receive all upcoming
 *      data frames
 *    * incoming data frames: buffered and pushed to the SubSource of the respective substream
 *
 * Multiplexing:
 *  * schedule incoming frames from multiple sources to be pushed onto the shared medium
 *    * control frames: as generated from the stage itself (should probably preferred over everything else)
 *    * Http2SubStream produced by the user handler: read and push initial frame ASAP
 *    * outgoing data frames for each of the substreams: will comprise the bulk of the data and is
 *      where any clever, prioritizing, etc. i.e. tbd later sending strategies will apply
 *
 * In the best case we could just flattenMerge the outgoing side (hoping for the best) but this will probably
 * not work because the sending decision relies on dynamic window size and settings information that will be
 * only available in this stage.
 */
class Http2ServerDemux extends GraphStage[BidiShape[Http2SubStream, FrameEvent, FrameEvent, Http2SubStream]] {

  import Http2ServerDemux._

  val frameIn = Inlet[FrameEvent]("Demux.frameIn")
  val frameOut = Outlet[FrameEvent]("Demux.frameOut")

  val substreamOut = Outlet[Http2SubStream]("Demux.substreamOut")
  val substreamIn = Inlet[Http2SubStream]("Demux.substreamIn")

  override val shape =
    BidiShape(substreamIn, frameOut, frameIn, substreamOut)

  def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with BufferedOutletSupport {
      logic ⇒

      final case class SubStream(
        streamId:                  Int,
        headers:                   HeadersFrame,
        initialStreamState:        StreamState,
        outlet:                    SubSourceOutlet[StreamFrameEvent],
        inlet:                     Option[SubSinkInlet[FrameEvent]],
        initialOutboundWindowLeft: Long
      ) extends BufferedOutlet[StreamFrameEvent](outlet) {
        var outboundWindowLeft = initialOutboundWindowLeft
        var state = initialStreamState
        var continuation: Boolean = false

        override def onDownstreamFinish(): Unit = {
          debug(s"Stream completed")
          completeStream(incomingStreams(streamId))
        }

      }

      override def preStart(): Unit = {
        pull(frameIn)
        pull(substreamIn)

        bufferedFrameOut.push(SettingsFrame(Nil)) // server side connection preface
      }

      // we should not handle streams later than the GOAWAY told us about with lastStreamId
      private var closedAfter: Option[Int] = None
      private var closing: Int = 0
      private var incomingStreams = mutable.Map.empty[Int, SubStream]
      private var lastStreamId: Int = 0
      private var totalOutboundWindowLeft = Http2Protocol.InitialWindowSize
      private var streamLevelWindow = Http2Protocol.InitialWindowSize
      // TODO: make me configurable
      private val streamTimeout = 500.milliseconds
      private val connectionTimeout = streamTimeout.plus(streamTimeout)

      def connectionClose(): Unit = {
        // FIXME: I'm not sure but this does not look working, any ideas?
        // if this is not propagated we somehow need a way to kill the tcp connection from the demuxer?
        debug("Closing the connection!")
        completeStage()
      }

      def completeStream(stream: SubStream): Unit = {
        incomingStreams(stream.streamId).state = StreamState.Closed
        // WINDOW_UPDATE or RST_STREAM frames can be received in this state for a short period after a DATA or HEADERS
        // frame containing an END_STREAM flag is sent. Until the remote peer receives and processes RST_STREAM or the
        // frame bearing the END_STREAM flag, it might send frames of these types. Endpoints MUST ignore WINDOW_UPDATE
        // or RST_STREAM frames received in this state, though endpoints MAY choose to treat frames that arrive a
        // significant time after sending END_STREAM as a connection error (Section 5.4.1) of type PROTOCOL_ERROR.
        materializer.scheduleOnce(streamTimeout, new Runnable {
          override def run(): Unit = {
            stream.inlet.foreach(_.cancel())
            stream.outlet.complete()
            incomingStreams.remove(stream.streamId)
          }
        })
      }

      def pushGOAWAY(errorCode: ErrorCode): Unit = {
        def shutdownGraceful(): Unit = {
          // waits for the termination of all substreams
          if (incomingStreams.isEmpty) connectionClose()
          else materializer.scheduleOnce(connectionTimeout, new Runnable {
            override def run(): Unit = shutdownGraceful()
          })
        }
        if (closing == 0) {
          closing += 1
          val last = lastStreamId
          closedAfter = Some(last)
          bufferedFrameOut.push(GoAwayFrame(last, errorCode))
          // A GOAWAY frame might not immediately precede closing of the connection; a receiver of a GOAWAY that has
          // no more use for the connection SHOULD still send a GOAWAY frame before terminating the connection.
          materializer.scheduleOnce(connectionTimeout, new Runnable {
            override def run(): Unit = {
              debug(s"Graceful Shutdown ${incomingStreams.isEmpty}")
              incomingStreams.foreach { case (id, _) ⇒ pushRST_STREAM(id, ErrorCode.CANCEL) }
              shutdownGraceful()
            }
          })
        } else if (closing == 3) {
          // FIXME: actually I guess we should also kill the streams before we do that?
          // An endpoint might choose to close a connection without sending a GOAWAY for misbehaving peers.
          connectionClose()
        }
      }

      def pushRST_STREAM(streamId: Int, errorCode: ErrorCode): Unit = {
        debug(s"RST STREAM FOR $streamId")
        bufferedFrameOut.push(RstStreamFrame(streamId, errorCode))
        completeStream(incomingStreams(streamId))
      }

      def isContinuation(streamId: Int): Boolean = {
        debug(s"Continuation state = ${incomingStreams(streamId).continuation}")
        incomingStreams(streamId).continuation
      }

      def isOpen(streamId: Int): Boolean = {
        val handler = incomingStreams(streamId)
        handler.state.equals(StreamState.Open) && !handler.continuation
      }

      setHandler(frameIn, new InHandler {
        def onPush(): Unit = {
          val in = grab(frameIn)
          debug(s"Incoming Frame: $in")
          in match {
            case WindowUpdateFrame(0, increment) ⇒
              totalOutboundWindowLeft += increment
              debug(f"outbound window is now $totalOutboundWindowLeft%10d after increment $increment%6d")
              bufferedFrameOut.tryFlush()

            case e: StreamFrameEvent if !Http2Compliance.isClientInitiatedStreamId(e.streamId) ⇒
              pushGOAWAY(ErrorCode.PROTOCOL_ERROR)

            case e: StreamFrameEvent if e.streamId > closedAfter.getOrElse(Int.MaxValue) ⇒
            // streams that will have a greater stream id than the one we sent with GO_AWAY will be ignored

            case headers @ HeadersFrame(streamId, endStream, endHeaders, fragment) if lastStreamId < streamId ⇒
              lastStreamId = streamId
              val subSource = new SubSourceOutlet[StreamFrameEvent](s"substream-out-$streamId")
              // A HEADERS frame carries the END_STREAM flag that signals the end of a stream.
              // However, a HEADERS frame with the END_STREAM flag set can be followed by CONTINUATION
              // frames on the same stream. Logically, the CONTINUATION frames are part of the HEADERS frame.
              val state = if (endStream) StreamState.HalfClosedRemote else StreamState.Open
              val handler = SubStream(streamId, headers, state, subSource, None, streamLevelWindow)
              if (!endHeaders) {
                handler.continuation = true
              }
              incomingStreams += streamId → handler // TODO optimise for lookup later on

              val sub = makeHttp2SubStream(handler)
              if (sub.initialFrame.endHeaders) dispatchSubstream(sub)
            // else we're expecting some continuation frames before we kick off the dispatch

            case e: StreamFrameEvent if !incomingStreams.contains(e.streamId) ⇒
              // if a stream is invalid we will GO_AWAY
              pushGOAWAY(ErrorCode.PROTOCOL_ERROR)

            case h: HeadersFrame if isOpen(h.streamId) ⇒
              // stream is open
              if (h.endStream) {
                // FIXME: add support for trailing headers
                incomingStreams(h.streamId).state = StreamState.HalfClosedRemote
              } else {
                pushGOAWAY(ErrorCode.PROTOCOL_ERROR)
              }

            case h: HeadersFrame ⇒
              // stream is half-closed or closed
              pushGOAWAY(ErrorCode.STREAM_CLOSED)

            case cont: ContinuationFrame if isContinuation(cont.streamId) ⇒
              // continue to build up headers (CONTINUATION come directly after HEADERS frame, and before DATA)
              val streamId = cont.streamId
              val streamHandler = incomingStreams(streamId)
              if (cont.endHeaders) {
                streamHandler.continuation = false
              }
              val updatedHandler = concatContinuationIntoHeaders(streamHandler, cont)
              val sub = makeHttp2SubStream(updatedHandler)
              if (updatedHandler.headers.endHeaders) dispatchSubstream(sub)

            case data: DataFrame if isOpen(data.streamId) ⇒
              // technically this case is the same as StreamFrameEvent, however we're handling it earlier in the match here for efficiency
              val stream = incomingStreams(data.streamId)
              stream.push(data) // pushing http entity, handle flow control from here somehow?
              if (data.endStream) {
                incomingStreams(stream.streamId).state = StreamState.HalfClosedRemote
              }

            case RstStreamFrame(streamId, errorCode) ⇒
              debug(s"Got an RST_STREAM: $streamId - $errorCode")
              completeStream(incomingStreams(streamId))

            case WindowUpdateFrame(streamId, increment) ⇒
              incomingStreams(streamId).outboundWindowLeft += increment
              debug(f"outbound window for [$streamId%3d] is now ${incomingStreams(streamId).outboundWindowLeft}%10d after increment $increment%6d")
              bufferedFrameOut.tryFlush()

            case e: GoAwayFrame ⇒
              // TODO: do we want to output the cause of connection close? only on NO_ERROR maybe?
              connectionClose()

            case e: StreamFrameEvent ⇒
              // FIXME: this is nasty
              e match {
                case _: ContinuationFrame ⇒ pushGOAWAY(ErrorCode.PROTOCOL_ERROR)
                case _ if incomingStreams(e.streamId).continuation ⇒ pushGOAWAY(ErrorCode.PROTOCOL_ERROR)
                // TODO: decicde if we are more strict and send a GOAWAY instead of a RST_STREAM
                case _ ⇒ pushRST_STREAM(e.streamId, ErrorCode.STREAM_CLOSED)
              }

            case SettingsFrame(settings) ⇒
              debug(s"Got ${settings.length} settings!")
              settings.foreach {
                case Setting(Http2Protocol.SettingIdentifier.SETTINGS_INITIAL_WINDOW_SIZE, value) ⇒
                  debug(s"Setting initial window to $value")
                  val delta = value - streamLevelWindow
                  streamLevelWindow = value
                  incomingStreams.values.foreach(_.outboundWindowLeft += delta)
                case Setting(id, value) ⇒ debug(s"Ignoring setting $id -> $value")
              }

              bufferedFrameOut.push(SettingsAckFrame)

            case PingFrame(true, _) ⇒ // ignore for now (we don't send any pings)
            case PingFrame(false, data) ⇒
              bufferedFrameOut.push(PingFrame(ack = true, data))

            case e ⇒
              debug(s"Got unhandled event $e")
            // ignore unknown frames
          }

          if (!isClosed(frameIn)) {
            // if we are in shutdown state
            // we should not pull more frames
            pull(frameIn)
          }
        }

        override def onUpstreamFailure(ex: Throwable): Unit = {
          // catches all compliance errors and handles them gracefully
          ex match {
            // every IllegalHttp2StreamIdException will be a GOAWAY with PROTOCOL_ERROR
            case e: Http2Compliance.IllegalHttp2StreamIdException ⇒
              pushGOAWAY(ErrorCode.PROTOCOL_ERROR)
            case e: IllegalHttp2FrameSize ⇒
              pushGOAWAY(ErrorCode.FRAME_SIZE_ERROR)

            case NonFatal(e) ⇒
              // handle every unhandled exception, by closing the connection
              connectionClose()
          }
        }
      })

      val bufferedSubStreamOutput = new BufferedOutlet[Http2SubStream](substreamOut)

      def dispatchSubstream(sub: Http2SubStream): Unit = bufferedSubStreamOutput.push(sub)

      setHandler(substreamIn, new InHandler {
        def onPush(): Unit = {
          val sub = grab(substreamIn)
          pull(substreamIn)
          bufferedFrameOut.push(sub.initialFrame)
          val subIn = new SubSinkInlet[FrameEvent](s"substream-in-${sub.streamId}")
          incomingStreams = incomingStreams.updated(sub.streamId, incomingStreams(sub.streamId).copy(inlet = Some(subIn)))
          subIn.pull()
          subIn.setHandler(new InHandler {
            def onPush(): Unit =
              bufferedFrameOut.pushWithTrigger(subIn.grab(), () ⇒
                if (!subIn.isClosed) subIn.pull())

            override def onUpstreamFinish(): Unit = {
              // FIXME: check for truncation (last frame must have endStream / endHeaders set)
              debug("UPSTREAM FINISH")
            }
          })
          sub.frames.runWith(subIn.sink)(subFusingMaterializer)

        }
      })

      val bufferedFrameOut = new BufferedOutletExtended[FrameEvent](frameOut) {
        override def doPush(elem: ElementAndTrigger): Unit = {
          elem.element match {
            case d @ DataFrame(streamId, _, pl) ⇒
              if (pl.size <= totalOutboundWindowLeft && pl.size <= incomingStreams(streamId).outboundWindowLeft) {
                super.doPush(elem)

                val size = pl.size
                totalOutboundWindowLeft -= size
                incomingStreams(streamId).outboundWindowLeft -= size

                debug(s"Pushed $size bytes of data for stream $streamId total window left: $totalOutboundWindowLeft per stream window left: ${incomingStreams(streamId).outboundWindowLeft}")
              } else {
                debug(s"Couldn't send because no window left. Size: ${pl.size} total: $totalOutboundWindowLeft per stream: ${incomingStreams(streamId).outboundWindowLeft}")
                // the resulting stream is in a error state
                // this actually means we need to send a RST_STREAM
                pushRST_STREAM(streamId, ErrorCode.FRAME_SIZE_ERROR)
              }
            case _ ⇒
              super.doPush(elem)
          }
        }
      }

      private def concatContinuationIntoHeaders(handler: SubStream, cont: ContinuationFrame) = {
        val concatHeaderBlockFragment = handler.headers.headerBlockFragment ++ cont.payload
        val moreCompleteHeadersFrame = HeadersFrame(handler.streamId, cont.endHeaders, cont.endHeaders, concatHeaderBlockFragment)
        // update the SubStream we keep around
        val moreCompleteHandler = handler.copy(headers = moreCompleteHeadersFrame)
        // FiXME: override copy?
        moreCompleteHandler.state = handler.state
        moreCompleteHandler.continuation = handler.continuation
        incomingStreams += handler.streamId → moreCompleteHandler
        moreCompleteHandler
      }

      def makeHttp2SubStream(handler: SubStream): Http2SubStream = {
        val headers = handler.headers
        val subStream = handler.outlet
        if (headers.endStream && headers.endHeaders) {
          Http2SubStream(headers, Source.empty)
        } else {
          // FIXME a bit naive but correct I think -- todo check the spec
          val remainingFrames = Source.fromGraph(subStream.source)
            .collect({
              case d: DataFrame ⇒ d
              case f            ⇒ throw new Exception("Unexpected frame type! We thought only DataFrames are accepted from here on. Was: " + f)
            })
          Http2SubStream(headers, remainingFrames)
        }
      }

      def debug(msg: String): Unit = println(msg)
    }

}

object Http2ServerDemux {
  sealed trait StreamState
  object StreamState {
    case object Idle extends StreamState
    case object Open extends StreamState
    case object Closed extends StreamState
    case object HalfClosedLocal extends StreamState
    case object HalfClosedRemote extends StreamState

    // for PUSH_PROMISE
    // case object ReservedLocal extends StreamState
    // case object ReservedRemote extends StreamState
  }

}
