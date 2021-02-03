/*
 * Copyright (C) 2009-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.annotation.InternalApi
import akka.http.impl.engine.http2.FrameEvent._
import akka.http.impl.engine.http2.Http2Protocol.ErrorCode
import akka.http.impl.engine.http2.Http2Protocol.ErrorCode.FLOW_CONTROL_ERROR
import akka.http.impl.engine.http2.Http2Protocol.SettingIdentifier
import akka.http.impl.engine.http2.RequestParsing.parseHeaderPair
import akka.http.impl.engine.parsing.HttpHeaderParser
import akka.http.scaladsl.model.AttributeKey
import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.model.HttpEntity.ChunkStreamPart
import akka.http.scaladsl.model.HttpEntity.LastChunk
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
import akka.stream.stage.TimerGraphStageLogic
import akka.util.ByteString
import com.github.ghik.silencer.silent

import scala.collection.immutable
import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

/**
 * INTERNAL API
 */
@InternalApi
private[http2] class Http2ClientDemux(http2Settings: Http2CommonSettings, masterHttpHeaderParser: HttpHeaderParser)
  extends Http2Demux(http2Settings, initialRemoteSettings = Nil, upgraded = false, isServer = false) {

  def wrapTrailingHeaders(headers: ParsedHeadersFrame): Option[ChunkStreamPart] = {
    val headerParser = masterHttpHeaderParser.createShallowCopy()
    Some(LastChunk(extension = "", headers.keyValuePairs.map {
      case (name, value) => parseHeaderPair(headerParser, name, value)
    }.toList))
  }

}

/**
 * INTERNAL API
 */
@InternalApi
private[http2] class Http2ServerDemux(http2Settings: Http2CommonSettings, initialRemoteSettings: immutable.Seq[Setting], upgraded: Boolean)
  extends Http2Demux(http2Settings, initialRemoteSettings, upgraded, isServer = true) {
  // We don't provide access to incoming trailing request headers on the server side
  @silent("not used")
  def wrapTrailingHeaders(headers: ParsedHeadersFrame): Option[ChunkStreamPart] = None

}

/**
 * INTERNAL API
 */
@InternalApi
private[http2] object ConfigurablePing {
  case object Tick
  // single instance used each time, only a single ping in flight ever so no need to use changing payloads
  val Ping = PingFrame(false, ByteString("abcdefgh"))

  object PingState {
    def apply(settings: Http2CommonSettings): PingState = {
      if (settings.pingInterval == 0.seconds) DisabledPingState
      else {
        // this means timeout is always one tick, but ping interval can be multiple ticks
        val tickInterval =
          if (settings.pingTimeout == Duration.Zero) settings.pingInterval
          else settings.pingInterval.min(settings.pingTimeout)
        new EnabledPingState(
          tickInterval,
          pingEveryNTickWithoutData = settings.pingInterval.toMillis / tickInterval.toMillis
        )
      }
    }
  }
  trait PingState {
    def tickInterval(): Option[FiniteDuration]
    def onDataFrameSeen(): Unit
    def onPingAck(): Unit
    def onTick(): Unit
    def clear(): Unit
    def shouldEmitPing(): Boolean
    def sendingPing(): Unit
    def pingAckOverdue(): Boolean
  }
  object DisabledPingState extends PingState {
    def tickInterval(): Option[FiniteDuration] = None
    def onDataFrameSeen(): Unit = ()
    def onPingAck(): Unit = ()
    def onTick(): Unit = ()
    def clear(): Unit = ()
    def shouldEmitPing(): Boolean = false
    def sendingPing(): Unit = ()
    def pingAckOverdue(): Boolean = false
  }
  final class EnabledPingState(tickInterval: FiniteDuration, pingEveryNTickWithoutData: Long) extends PingState {
    private var ticksWithoutData = 0L
    private var ticksSincePing = 0L
    private var pingInFlight = false

    def tickInterval(): Option[FiniteDuration] = Some(tickInterval)

    def onDataFrameSeen(): Unit = {
      ticksWithoutData = 0L
    }
    def onPingAck(): Unit = {
      ticksSincePing = 0L
      pingInFlight = false
    }
    def onTick(): Unit = {
      ticksWithoutData += 1L
      if (pingInFlight) ticksSincePing += 1L
    }
    def clear(): Unit = {
      ticksWithoutData = 0L
      ticksSincePing = 0L
    }
    def shouldEmitPing(): Boolean =
      ticksWithoutData > 0L && ticksWithoutData % pingEveryNTickWithoutData == 0

    def sendingPing(): Unit = {
      ticksSincePing = 0L
      pingInFlight = true
    }

    def pingAckOverdue(): Boolean = {
      pingInFlight && ticksSincePing > 1L
    }
  }
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
private[http2] abstract class Http2Demux(http2Settings: Http2CommonSettings, initialRemoteSettings: immutable.Seq[Setting], upgraded: Boolean, isServer: Boolean) extends GraphStage[BidiShape[Http2SubStream, FrameEvent, FrameEvent, Http2SubStream]] {
  stage =>
  val frameIn = Inlet[FrameEvent]("Demux.frameIn")
  val frameOut = Outlet[FrameEvent]("Demux.frameOut")

  val substreamOut = Outlet[Http2SubStream]("Demux.substreamOut")
  val substreamIn = Inlet[Http2SubStream]("Demux.substreamIn")

  override val shape =
    BidiShape(substreamIn, frameOut, frameIn, substreamOut)

  def wrapTrailingHeaders(headers: ParsedHeadersFrame): Option[HttpEntity.ChunkStreamPart]

  def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new TimerGraphStageLogic(shape) with Http2MultiplexerSupport with Http2StreamHandling with GenericOutletSupport with StageLogging with LogHelper {
      logic =>

      def wrapTrailingHeaders(headers: ParsedHeadersFrame): Option[HttpEntity.ChunkStreamPart] = stage.wrapTrailingHeaders(headers)

      override def isServer: Boolean = stage.isServer
      override def settings: Http2CommonSettings = http2Settings
      override def isUpgraded: Boolean = upgraded

      override protected def logSource: Class[_] = if (isServer) classOf[Http2ServerDemux] else classOf[Http2ClientDemux]

      case object CompletionTimeout

      def frameOutFinished(): Unit = {
        // make sure we clean up/fail substreams with a custom failure before stage is canceled
        // and substream autoclean kicks in
        shutdownStreamHandling()
      }

      override def pushFrameOut(event: FrameEvent): Unit = {
        pingState.onDataFrameSeen()
        frameOut.push(event)
      }

      val multiplexer = createMultiplexer(StreamPrioritizer.first())
      frameOut.setHandler(multiplexer)

      val pingState = ConfigurablePing.PingState(http2Settings)

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

        pingState.tickInterval().foreach(interval =>
          // to limit overhead rather than constantly rescheduling a timer and looking at system time we use a constant timer
          schedulePeriodically(ConfigurablePing.Tick, interval)
        )
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
      def pullFrameIn(): Unit = if (allowReadingIncomingFrames && !hasBeenPulled(frameIn) && !isClosed(frameIn)) pull(frameIn)

      def tryPullSubStreams(): Unit = {
        if (!hasBeenPulled(substreamIn) && !isClosed(substreamIn)) {
          // While we don't support PUSH_PROMISE there's only capacity control on the client
          if (isServer) pull(substreamIn)
          else if (hasCapacityToCreateStreams) pull(substreamIn)
        }
      }

      // -----------------------------------------------------------------
      setHandler(frameIn, new InHandler {

        def onPush(): Unit = {
          val frame = grab(frameIn)
          frame match {
            case _: PingFrame => // handle later
            case _            => pingState.onDataFrameSeen()
          }
          frame match {
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

            case PingFrame(true, data) =>
              if (data != ConfigurablePing.Ping.data) {
                // We only ever push static data, responding with anything else is wrong
                pushGOAWAY(ErrorCode.PROTOCOL_ERROR, "Ping ack contained unexpected data")
              } else {
                pingState.onPingAck()
              }
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

      // -----------------------------------------------------------------
      // FIXME: What if user handler doesn't pull in new substreams? Should we reject them
      //        after a while or buffer only a limited amount?
      val bufferedSubStreamOutput = new BufferedOutlet[Http2SubStream](substreamOut)
      override def dispatchSubstream(initialHeaders: ParsedHeadersFrame, data: Source[Any, Any], correlationAttributes: Map[AttributeKey[_], _]): Unit =
        bufferedSubStreamOutput.push(Http2SubStream(initialHeaders, data, correlationAttributes))

      // -----------------------------------------------------------------
      override def onAllStreamsClosed(): Unit = completeIfDone()
      override def onAllDataFlushed(): Unit = completeIfDone()

      private def completeIfDone(): Unit = {
        val noMoreOutgoingStreams = isClosed(substreamIn) && activeStreamCount() == 0
        val allOutgoingDataFlushed = isClosed(frameOut) || multiplexer.hasFlushedAllData
        if (noMoreOutgoingStreams && allOutgoingDataFlushed) {
          cancel(frameIn)
          complete(frameOut)
          // Using complete here (instead of a simpler `completeStage`) will make sure the buffer can be
          // drained by the user before completely shutting down the stage finally.
          bufferedSubStreamOutput.complete()
        }
      }

      // -----------------------------------------------------------------
      setHandler(substreamIn, new InHandler {
        def onPush(): Unit = {
          val sub = grab(substreamIn)
          handleOutgoingCreated(sub)
          // Once the incoming stream is handled, we decide if we need to pull more.
          tryPullSubStreams()
        }

        override def onUpstreamFinish(): Unit =
          if (isServer) // on the server side conservatively shut everything down if user handler completes prematurely
            super.onUpstreamFinish()
          else { // on the client side allow ongoing responses to be delivered for a while even if requests are done
            completeIfDone()
            scheduleOnce(CompletionTimeout, settings.completionTimeout)
          }
      })

      /**
       * Tune this peer to the remote Settings.
       *
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

      override protected def onTimer(timerKey: Any): Unit = timerKey match {
        case ConfigurablePing.Tick =>
          // don't do anything unless there are active streams
          if (activeStreamCount() > 0) {
            pingState.onTick()
            if (pingState.pingAckOverdue()) {
              pushGOAWAY(ErrorCode.PROTOCOL_ERROR, "Ping ack timeout")
            } else if (pingState.shouldEmitPing()) {
              pingState.sendingPing()
              multiplexer.pushControlFrame(ConfigurablePing.Ping)
            }
          } else {
            pingState.clear()
          }
        case CompletionTimeout =>
          info("Timeout: Peer didn't finish in-flight requests. Closing pending HTTP/2 streams. Increase this timeout via the 'completion-timeout' setting.")
          completeStage()
      }

      override def postStop(): Unit = {
        shutdownStreamHandling()
      }
    }
}
