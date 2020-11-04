/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.annotation.InternalApi
import akka.http.impl.engine.http2.FrameEvent._
import akka.http.impl.engine.http2.Http2Protocol.ErrorCode
import akka.http.impl.engine.http2.Http2Protocol.ErrorCode.FLOW_CONTROL_ERROR
import akka.http.impl.engine.http2.Http2Protocol.SettingIdentifier
import akka.http.scaladsl.model.AttributeKey
import akka.http.scaladsl.model.HttpEntity.{ Chunk, ChunkStreamPart, LastChunk }
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.settings.Http2CommonSettings
import akka.macros.LogHelper
import akka.stream.Attributes
import akka.stream.BidiShape
import akka.stream.Inlet
import akka.stream.Outlet
import akka.stream.impl.io.ByteStringParser.ParsingException
import akka.stream.scaladsl.Source
import akka.stream.stage.GraphStage
import akka.stream.stage.GraphStageLogic
import akka.stream.stage.InHandler
import akka.stream.stage.StageLogging
import akka.util.ByteString
import com.github.ghik.silencer.silent

import scala.collection.immutable
import scala.util.control.NonFatal

/** Currently only used as log source */
@InternalApi
private[http2] class Http2ClientDemux(http2Settings: Http2CommonSettings, initialRemoteSettings: immutable.Seq[Setting])
  extends Http2Demux[ChunkStreamPart, ChunkedHttp2SubStream](http2Settings, initialRemoteSettings, upgraded = false, isServer = false) {

  override def createSubstream(initialHeaders: ParsedHeadersFrame, data: Source[ChunkStreamPart, Any], correlationAttributes: Map[AttributeKey[_], _]): ChunkedHttp2SubStream =
    ChunkedHttp2SubStream(initialHeaders, data, correlationAttributes)

  def wrapData(bytes: akka.util.ByteString): ChunkStreamPart = Chunk(bytes)
  def wrapTrailingHeaders(headers: ParsedHeadersFrame): Option[ChunkStreamPart] = Some(LastChunk(extension = "", headers.keyValuePairs.map {
    // TODO convert to modeled headers
    case (k, v) => RawHeader(k, v)
  }.toList))

}

private[http2] class Http2ServerDemux(http2Settings: Http2CommonSettings, initialRemoteSettings: immutable.Seq[Setting], upgraded: Boolean)
  extends Http2Demux[ByteString, ByteHttp2SubStream](http2Settings, initialRemoteSettings, upgraded, isServer = true) {

  override def createSubstream(initialHeaders: ParsedHeadersFrame, data: Source[ByteString, Any], correlationAttributes: Map[AttributeKey[_], _]): ByteHttp2SubStream =
    ByteHttp2SubStream(initialHeaders, data, correlationAttributes)

  def wrapData(bytes: akka.util.ByteString): ByteString = bytes

  // We don't provide access to incoming trailing request headers on the server side
  @silent("not used")
  def wrapTrailingHeaders(headers: ParsedHeadersFrame): Option[ByteString] = None
}

/**
 * INTERNAL API
 *
 * This stage contains all control logic for handling frames and (de)muxing data to/from substreams.
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
 *
 * @param initialRemoteSettings sequence of settings received on the initial header sent from
 *                              the client in an ' HTTP2-Settings:' header. This parameter should only be used
 *                              on the server end of a connection.
 */
@InternalApi
private[http2] abstract class Http2Demux[T, S <: Http2SubStream[T]](http2Settings: Http2CommonSettings, initialRemoteSettings: immutable.Seq[Setting], upgraded: Boolean, isServer: Boolean) extends GraphStage[BidiShape[Http2SubStream[Any], FrameEvent, FrameEvent, S]] {
  stage =>
  val frameIn = Inlet[FrameEvent]("Demux.frameIn")
  val frameOut = Outlet[FrameEvent]("Demux.frameOut")

  val substreamOut = Outlet[S]("Demux.substreamOut")
  val substreamIn = Inlet[Http2SubStream[Any]]("Demux.substreamIn")

  override val shape =
    BidiShape(substreamIn, frameOut, frameIn, substreamOut)

  def createSubstream(initialHeaders: ParsedHeadersFrame, data: Source[T, Any], correlationAttributes: Map[AttributeKey[_], _]): S
  def wrapData(bytes: akka.util.ByteString): T
  def wrapTrailingHeaders(headers: ParsedHeadersFrame): Option[T]

  def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with Http2MultiplexerSupport with Http2StreamHandling[T] with GenericOutletSupport with StageLogging with LogHelper {
      logic =>

      override def isServer: Boolean = stage.isServer
      override def settings: Http2CommonSettings = http2Settings
      override def isUpgraded: Boolean = upgraded

      override def wrapData(bytes: akka.util.ByteString): T = Http2Demux.this.wrapData(bytes)
      override def wrapTrailingHeaders(headers: ParsedHeadersFrame): Option[T] = Http2Demux.this.wrapTrailingHeaders(headers)

      override protected def logSource: Class[_] = if (isServer) classOf[Http2ServerDemux] else classOf[Http2ClientDemux]

      val multiplexer = createMultiplexer(frameOut, StreamPrioritizer.first())

      // Send initial settings based on the local application.conf. For simplicity, these settings are
      // enforced immediately even before the acknowledgement is received.
      // Reminder: the receiver of a SETTINGS frame must process them in the order they are received.
      val initialLocalSettings: immutable.Seq[Setting] = immutable.Seq(
        Setting(SettingIdentifier.SETTINGS_MAX_CONCURRENT_STREAMS, http2Settings.maxConcurrentStreams)
      ) ++
        immutable.Seq(Setting(SettingIdentifier.SETTINGS_ENABLE_PUSH, 0)).filter(_ => !isServer) // only on client

      override def preStart(): Unit = {
        if (initialRemoteSettings.nonEmpty) {
          debug(s"Applying ${initialRemoteSettings.length} initial settings!")
          applyRemoteSettings(initialRemoteSettings)
        }

        pullFrameIn()
        pull(substreamIn)

        // both client and server must send a settings frame as first frame
        multiplexer.pushControlFrame(SettingsFrame(initialLocalSettings))
      }

      override def pushGOAWAY(errorCode: ErrorCode, debug: String): Unit = {
        val frame = GoAwayFrame(lastStreamId(), errorCode, ByteString(debug))
        multiplexer.pushControlFrame(frame)
        // FIXME: handle the connection closing according to the specification
      }
      private[this] var allowReadingIncomingFrames: Boolean = true
      override def allowReadingIncomingFrames(allow: Boolean): Unit = {
        if (allow != allowReadingIncomingFrames)
          if (allow) {
            debug("Resume reading incoming frames")
            if (!hasBeenPulled(frameIn)) pull(frameIn)
          } else debug("Suspended reading incoming frames") // can't retract pending pull but that's ok

        allowReadingIncomingFrames = allow
      }
      def pullFrameIn(): Unit = if (allowReadingIncomingFrames && !hasBeenPulled(frameIn)) pull(frameIn)

      def tryPullSubStreams(): Unit = {
        if (!hasBeenPulled(substreamIn) && !isClosed(substreamIn)) {
          // While we don't support PUSH_PROMISE there's only capacity control on the client
          if (isServer) pull(substreamIn)
          else if (hasCapacityToCreateStreams) pull(substreamIn)
        }
      }

      setHandler(frameIn, new InHandler {

        def onPush(): Unit = {
          grab(frameIn) match {
            case WindowUpdateFrame(streamId, increment) if streamId == 0 /* else fall through to StreamFrameEvent */ => multiplexer.updateConnectionLevelWindow(increment)
            case p: PriorityFrame => multiplexer.updatePriority(p)
            case s: StreamFrameEvent => handleStreamEvent(s)

            case SettingsFrame(settings) =>
              if (settings.nonEmpty) debug(s"Got ${settings.length} settings!")

              val settingsAppliedOk = applyRemoteSettings(settings)
              if (settingsAppliedOk) {
                multiplexer.pushControlFrame(SettingsAckFrame(settings))
              }

            case SettingsAckFrame(_) =>
            // Currently, we only expect an ack for the initial settings frame, sent
            // above in preStart. Since only some settings are supported, and those
            // settings are non-modifiable and known at construction time, these settings
            // are enforced from the start of the connection so there's no need to invoke
            // `enforceSettings(initialLocalSettings)`

            case PingFrame(true, _)  =>
            // ignore for now (we don't send any pings)
            case PingFrame(false, data) =>
              multiplexer.pushControlFrame(PingFrame(ack = true, data))

            case e =>
              debug(s"Got unhandled event $e")
            // ignore unknown frames
          }
          pullFrameIn()
        }

        override def onUpstreamFailure(ex: Throwable): Unit = {
          ex match {
            // every IllegalHttp2StreamIdException will be a GOAWAY with PROTOCOL_ERROR
            case e: Http2Compliance.IllegalHttp2StreamIdException =>
              pushGOAWAY(ErrorCode.PROTOCOL_ERROR, e.getMessage)

            case e: Http2Compliance.Http2ProtocolException =>
              pushGOAWAY(e.errorCode, e.getMessage)

            case e: Http2Compliance.Http2ProtocolStreamException =>
              resetStream(e.streamId, e.errorCode)

            case e: ParsingException =>
              e.getCause match {
                case null  => super.onUpstreamFailure(e) // fail with the raw parsing exception
                case cause => onUpstreamFailure(cause) // unwrap the cause, which should carry ComplianceException and recurse
              }

            // handle every unhandled exception
            case NonFatal(e) =>
              super.onUpstreamFailure(e)
          }
        }
      })

      // FIXME: What if user handler doesn't pull in new substreams? Should we reject them
      //        after a while or buffer only a limited amount? We should also be able to
      //        keep the buffer limited to the number of concurrent streams as negotiated
      //        with the other side.
      val bufferedSubStreamOutput = new BufferedOutlet[S](substreamOut)
      override def dispatchSubstream(initialHeaders: ParsedHeadersFrame, data: Source[T, Any], correlationAttributes: Map[AttributeKey[_], _]): Unit =
        bufferedSubStreamOutput.push(createSubstream(initialHeaders, data, correlationAttributes))

      setHandler(substreamIn, new InHandler {
        def onPush(): Unit = {
          val sub = grab(substreamIn)
          handleOutgoingCreated(sub)
          // Once the incoming stream is handled, we decide if we need to pull more.
          tryPullSubStreams()
        }
      })

      /**
       * Tune this peer to the remote Settings.
       * @param settings settings sent from the other peer (or injected via the
       *                 "HTTP2-Settings" in "h2c").
       * @return true if settings were applied successfully, false if some ERROR
       *         was raised. When raising an ERROR, this method already pushes the
       *         error back to the peer.
       */
      private def applyRemoteSettings(settings: immutable.Seq[Setting]): Boolean = {
        var settingsAppliedOk = true

        settings.foreach {
          case Setting(Http2Protocol.SettingIdentifier.SETTINGS_INITIAL_WINDOW_SIZE, value) =>
            if (value >= 0) {
              debug(s"Setting initial window to $value")
              multiplexer.updateDefaultWindow(value)
            } else {
              pushGOAWAY(FLOW_CONTROL_ERROR, s"Invalid value for SETTINGS_INITIAL_WINDOW_SIZE: $value")
              settingsAppliedOk = false
            }
          case Setting(Http2Protocol.SettingIdentifier.SETTINGS_MAX_FRAME_SIZE, value) =>
            multiplexer.updateMaxFrameSize(value)
          case Setting(Http2Protocol.SettingIdentifier.SETTINGS_MAX_CONCURRENT_STREAMS, value) =>
            setMaxConcurrentStreams(value)
            // once maxConcurrentStreams is updated, see if we can pull again
            tryPullSubStreams()
          case Setting(id, value) =>
            debug(s"Ignoring setting $id -> $value (in Demux)")
        }
        settingsAppliedOk
      }

      override def postStop(): Unit = {
        shutdownStreamHandling()
      }
    }
}
