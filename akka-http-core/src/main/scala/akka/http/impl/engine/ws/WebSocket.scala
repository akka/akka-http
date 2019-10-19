/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.ws

import java.util.Random

import akka.NotUsed
import akka.annotation.InternalApi
import akka.event.LoggingAdapter
import akka.http.impl.engine.ws.FrameOutHandler.Input
import akka.http.impl.engine.ws.Protocol.Opcode
import akka.http.impl.settings.WebSocketSettingsImpl
import akka.http.impl.util.StreamUtils
import akka.util.ByteString

import scala.concurrent.duration._
import akka.stream._
import akka.stream.scaladsl._
import akka.stream.stage._
import akka.http.scaladsl.model.ws._
import akka.http.scaladsl.settings.WebSocketSettings

/**
 * INTERNAL API
 *
 * Defines components of the websocket stack.
 */
@InternalApi
private[http] object WebSocket {
  import FrameHandler._

  /**
   * A stack of all the higher WS layers between raw frames and the user API.
   */
  def stack(
    serverSide:        Boolean,
    websocketSettings: WebSocketSettings,
    closeTimeout:      FiniteDuration    = 3.seconds, // TODO put close timeout into the settings?
    log:               LoggingAdapter): BidiFlow[FrameEvent, Message, Message, FrameEvent, NotUsed] =
    masking(serverSide, websocketSettings.randomFactory) atop
      frameHandling(serverSide, closeTimeout, log) atop
      periodicKeepAlive(websocketSettings) atop
      messageAPI(serverSide, closeTimeout)

  /** The lowest layer that implements the binary protocol */
  def framing: BidiFlow[ByteString, FrameEvent, FrameEvent, ByteString, NotUsed] =
    BidiFlow.fromFlows(
      Flow[ByteString].via(FrameEventParser),
      Flow[FrameEvent].via(new FrameEventRenderer))
      .named("ws-framing")

  /** The layer that handles masking using the rules defined in the specification */
  def masking(serverSide: Boolean, maskingRandomFactory: () => Random): BidiFlow[FrameEvent, FrameEventOrError, FrameEvent, FrameEvent, NotUsed] =
    Masking(serverSide, maskingRandomFactory)
      .named("ws-masking")

  /** The layer that transparently injects (if enabled) keepAlive Ping or Pong messages when connection is idle */
  def periodicKeepAlive(settings: WebSocketSettings): BidiFlow[FrameHandler.Output, FrameHandler.Output, FrameOutHandler.Input, FrameOutHandler.Input, NotUsed] = {
    settings.periodicKeepAliveMaxIdle match {
      case maxIdle: FiniteDuration =>

        val noCustomData = WebSocketSettingsImpl.hasNoCustomPeriodicKeepAliveData(settings)
        val mkFrame = settings.periodicKeepAliveMode match {
          case "ping" if noCustomData => mkDirectAnswerPing // sending Ping should result in a Pong back
          case "ping" => () => DirectAnswer(FrameEvent.fullFrame(Opcode.Ping, None, settings.periodicKeepAliveData(), fin = true))

          case "pong" if noCustomData => mkDirectAnswerPong // sending Pong means we do not expect a reply
          case "pong" => () => DirectAnswer(FrameEvent.fullFrame(Opcode.Pong, None, settings.periodicKeepAliveData(), fin = true))

          case other => throw new IllegalArgumentException(s"Unsupported periodic-keep-alive-mode. " +
            s"Found: [$other] however only [ping] and [pong] are supported")
        }

        BidiFlow.fromFlows(
          Flow[FrameHandler.Output].keepAlive(maxIdle, mkFrame),
          Flow[Input]
        )
      case _ =>
        BidiFlow.identity
    }
  }

  private[this] final val PingFullFrame: FrameStart = FrameEvent.fullFrame(Opcode.Ping, None, ByteString.empty, fin = true)
  private[this] final val mkDirectAnswerPing = () => DirectAnswer(PingFullFrame)

  private[this] final val PongFullFrame: FrameStart = FrameEvent.fullFrame(Opcode.Pong, None, ByteString.empty, fin = true)
  private[this] final val mkDirectAnswerPong = () => DirectAnswer(PongFullFrame)

  /**
   * The layer that implements all low-level frame handling, like handling control frames, collecting messages
   * from frames, decoding text messages, close handling, etc.
   */
  def frameHandling(
    serverSide:   Boolean,
    closeTimeout: FiniteDuration,
    log:          LoggingAdapter): BidiFlow[FrameEventOrError, FrameHandler.Output, FrameOutHandler.Input, FrameStart, NotUsed] =
    BidiFlow.fromFlows(
      FrameHandler.create(server = serverSide),
      FrameOutHandler.create(serverSide, closeTimeout, log))
      .named("ws-frame-handling")

  /* Completes this branch of the flow if no more messages are expected and converts close codes into errors */
  private object PrepareForUserHandler extends GraphStage[FlowShape[MessagePart, MessagePart]] {
    val in = Inlet[MessagePart]("PrepareForUserHandler.in")
    val out = Outlet[MessagePart]("PrepareForUserHandler.out")
    override val shape = FlowShape(in, out)
    override def initialAttributes: Attributes = Attributes.name("PrepareForUserHandler")

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) with InHandler with OutHandler {
      var inMessage = false
      override def onPush(): Unit = grab(in) match {
        case PeerClosed(code, reason) =>
          if (code.exists(Protocol.CloseCodes.isError)) failStage(new PeerClosedConnectionException(code.get, reason))
          else if (inMessage) failStage(new ProtocolException(s"Truncated message, peer closed connection in the middle of message."))
          else completeStage()
        case ActivelyCloseWithCode(code, reason) =>
          if (code.exists(Protocol.CloseCodes.isError)) failStage(new ProtocolException(s"Closing connection with error code $code"))
          else failStage(new IllegalStateException("Regular close from FrameHandler is unexpected"))
        case x: MessageDataPart =>
          inMessage = !x.last
          push(out, x)
        case x => push(out, x)
      }
      override def onPull(): Unit = pull(in)
      setHandlers(in, out, this)
    }
  }

  /**
   * The layer that provides the high-level user facing API on top of frame handling.
   */
  def messageAPI(
    serverSide:   Boolean,
    closeTimeout: FiniteDuration): BidiFlow[FrameHandler.Output, Message, Message, FrameOutHandler.Input, NotUsed] = {
    /* Collects user-level API messages from MessageDataParts */
    val collectMessage: Flow[MessageDataPart, Message, NotUsed] =
      Flow[MessageDataPart]
        .prefixAndTail(1)
        .map {
          case (TextMessagePart(text, true) +: Nil, remaining) =>
            StreamUtils.cancelSource(remaining)(StreamUtils.OnlyRunInGraphInterpreterContext)
            TextMessage.Strict(text)
          case ((first @ TextMessagePart(_, false)) +: Nil, remaining) =>
            TextMessage(
              (Source.single(first) ++ remaining)
                .collect { case t: TextMessagePart if t.data.nonEmpty => t.data }
            )
          case (BinaryMessagePart(data, true) +: Nil, remaining) =>
            StreamUtils.cancelSource(remaining)(StreamUtils.OnlyRunInGraphInterpreterContext)
            BinaryMessage.Strict(data)
          case ((first @ BinaryMessagePart(_, false)) +: Nil, remaining) =>
            BinaryMessage(
              (Source.single(first) ++ remaining)
                .collect { case b: BinaryMessagePart if b.data.nonEmpty => b.data }
            )
        }

    def prepareMessages: Flow[MessagePart, Message, NotUsed] =
      Flow[MessagePart]
        .via(PrepareForUserHandler)
        .splitAfter(_.isMessageEnd)
        .collect {
          case m: MessageDataPart => m
        }
        .via(collectMessage)
        .concatSubstreams
        .named("ws-prepare-messages")

    def renderMessages: Flow[Message, FrameStart, NotUsed] =
      MessageToFrameRenderer.create(serverSide)
        .named("ws-render-messages")

    BidiFlow.fromGraph(GraphDSL.create() { implicit b =>
      import GraphDSL.Implicits._

      val split = b.add(BypassRouter)
      val tick = Source.tick(closeTimeout, closeTimeout, Tick)
      val merge = b.add(BypassMerge)
      val messagePreparation = b.add(prepareMessages)
      val messageRendering = b.add(renderMessages.via(LiftCompletions))

      // user handler
      split.out1 ~> messagePreparation
      messageRendering.outlet ~> merge.in1

      // bypass
      split.out0 ~> merge.in0

      // timeout support
      tick ~> merge.in2

      BidiShape(
        split.in,
        messagePreparation.out,
        messageRendering.in,
        merge.out)
    }.named("ws-message-api"))
  }

  private case object BypassRouter extends GraphStage[FanOutShape2[Output, BypassEvent, MessagePart]] {
    private val outputIn = Inlet[Output]("BypassRouter.outputIn")
    private val bypassOut = Outlet[BypassEvent]("BypassRouter.bypassOut")
    private val messageOut = Outlet[MessagePart]("BypassRouter.messageOut")

    override def initialAttributes = Attributes.name("BypassRouter")

    val shape = new FanOutShape2(outputIn, bypassOut, messageOut)

    def createLogic(effectiveAttributes: Attributes) = new GraphStageLogic(shape) {

      setHandler(outputIn, new InHandler {
        override def onPush(): Unit = {
          grab(outputIn) match {
            case b: BypassEvent with MessagePart => emit(bypassOut, b, () => emit(messageOut, b, pullIn))
            case b: BypassEvent                  => emit(bypassOut, b, pullIn)
            case m: MessagePart                  => emit(messageOut, m, pullIn)
          }
        }
      })
      val pullIn = () => tryPull(outputIn)

      setHandler(bypassOut, eagerTerminateOutput)
      setHandler(messageOut, ignoreTerminateOutput)

      override def preStart(): Unit = {
        pullIn()
      }
    }
  }

  private case object BypassMerge extends GraphStage[FanInShape3[BypassEvent, AnyRef, Tick.type, AnyRef]] {
    private val bypassIn = Inlet[BypassEvent]("BypassMerge.bypassIn")
    private val messageIn = Inlet[AnyRef]("BypassMerge.messageIn")
    private val tickIn = Inlet[Tick.type]("BypassMerge.tickIn")
    private val messageOut = Outlet[AnyRef]("BypassMerge.messageOut")

    override def initialAttributes = Attributes.name("BypassMerge")

    val shape = new FanInShape3(bypassIn, messageIn, tickIn, messageOut)

    def createLogic(effectiveAttributes: Attributes) = new GraphStageLogic(shape) {

      class PassAlong[T <: AnyRef](from: Inlet[T]) extends InHandler with (() => Unit) {
        override def apply(): Unit = tryPull(from)
        override def onPush(): Unit = emit(messageOut, grab(from), this)
        override def onUpstreamFinish(): Unit =
          if (isClosed(bypassIn) && isClosed(messageIn)) completeStage()
      }
      setHandler(bypassIn, new PassAlong(bypassIn))
      setHandler(messageIn, new PassAlong(messageIn))
      passAlong(tickIn, messageOut, doFinish = false, doFail = false)

      setHandler(messageOut, eagerTerminateOutput)

      override def preStart(): Unit = {
        pull(bypassIn)
        pull(messageIn)
        pull(tickIn)
      }
    }
  }

  private case object LiftCompletions extends GraphStage[FlowShape[FrameStart, AnyRef]] {
    private val in = Inlet[FrameStart]("LiftCompletions.in")
    private val out = Outlet[AnyRef]("LiftCompletions.out")

    override def initialAttributes = Attributes.name("LiftCompletions")

    val shape = new FlowShape(in, out)

    def createLogic(effectiveAttributes: Attributes) = new GraphStageLogic(shape) {
      setHandler(out, new OutHandler {
        override def onPull(): Unit = pull(in)
      })
      setHandler(in, new InHandler {
        override def onPush(): Unit = push(out, grab(in))
        override def onUpstreamFinish(): Unit = emit(out, UserHandlerCompleted, () => completeStage())
        override def onUpstreamFailure(ex: Throwable): Unit = emit(out, UserHandlerErredOut(ex), () => completeStage())
      })
    }
  }

  case object Tick
}
