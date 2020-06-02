/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.client.pool

import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeoutException

import akka.annotation.InternalApi
import akka.http.impl.engine.client.PoolFlow.RequestContext
import akka.http.impl.util._
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.settings.ConnectionPoolSettings

import scala.concurrent.duration._
import scala.util.control.NoStackTrace
import scala.util.{ Failure, Success, Try }

/**
 * Internal API
 *
 * Interface between slot states and the actual slot.
 */
@InternalApi
private[pool] abstract class SlotContext {
  def openConnection(): Unit
  def isConnectionClosed: Boolean

  def dispatchResponseResult(req: RequestContext, result: Try[HttpResponse]): Unit

  def willCloseAfter(res: HttpResponse): Boolean

  def debug(msg: String): Unit
  def debug(msg: String, arg1: AnyRef): Unit
  def debug(msg: String, arg1: AnyRef, arg2: AnyRef): Unit
  def debug(msg: String, arg1: AnyRef, arg2: AnyRef, arg3: AnyRef): Unit

  def warning(msg: String): Unit
  def warning(msg: String, arg1: AnyRef): Unit

  def settings: ConnectionPoolSettings
}

/* Internal API */
@InternalApi
private[pool] sealed abstract class SlotState extends Product {
  def isIdle: Boolean
  def isConnected: Boolean

  def onPreConnect(ctx: SlotContext): SlotState = illegalState(ctx, "onPreConnect")
  def onConnectionAttemptSucceeded(ctx: SlotContext, outgoingConnection: Http.OutgoingConnection): SlotState = illegalState(ctx, "onConnectionAttemptSucceeded")
  def onConnectionAttemptFailed(ctx: SlotContext, cause: Throwable): SlotState = illegalState(ctx, "onConnectionAttemptFailed")

  def onNewConnectionEmbargo(ctx: SlotContext, embargoDuration: FiniteDuration): SlotState = illegalState(ctx, "onNewConnectionEmbargo")

  def onNewRequest(ctx: SlotContext, requestContext: RequestContext): SlotState = illegalState(ctx, "onNewRequest")

  def onRequestDispatched(ctx: SlotContext): SlotState = illegalState(ctx, "onRequestDispatched")

  /** Will be called either immediately if the request entity is strict or otherwise later */
  def onRequestEntityCompleted(ctx: SlotContext): SlotState = illegalState(ctx, "onRequestEntityCompleted")
  def onRequestEntityFailed(ctx: SlotContext, cause: Throwable): SlotState = illegalState(ctx, "onRequestEntityFailed")

  def onResponseReceived(ctx: SlotContext, response: HttpResponse): SlotState = illegalState(ctx, "onResponseReceived")

  /** Called when the response out port is ready to receive a further response (successful or failed) */
  def onResponseDispatchable(ctx: SlotContext): SlotState = illegalState(ctx, "onResponseDispatchable")

  def onResponseEntitySubscribed(ctx: SlotContext): SlotState = illegalState(ctx, "onResponseEntitySubscribed")

  /** Will be called either immediately if the response entity is strict or otherwise later */
  def onResponseEntityCompleted(ctx: SlotContext): SlotState = illegalState(ctx, "onResponseEntityCompleted")
  def onResponseEntityFailed(ctx: SlotContext, cause: Throwable): SlotState = illegalState(ctx, "onResponseEntityFailed")

  def onConnectionCompleted(ctx: SlotContext): SlotState = illegalState(ctx, "onConnectionCompleted")
  def onConnectionFailed(ctx: SlotContext, cause: Throwable): SlotState = illegalState(ctx, "onConnectionFailed")

  def onTimeout(ctx: SlotContext): SlotState = illegalState(ctx, "onTimeout")

  def onShutdown(ctx: SlotContext): Unit = ()

  /** A slot can define a timeout for that state after which onTimeout will be called. */
  def stateTimeout: Duration = Duration.Inf

  protected def illegalState(ctx: SlotContext, what: String): SlotState = {
    ctx.debug(s"Got unexpected event [$what] in state [$name]]")
    throw new IllegalStateException(s"Cannot [$what] when in state [$name]")
  }

  def name: String = productPrefix
}

/**
 * Internal API
 *
 * Implementation of slot logic that is completed decoupled from the machinery bits which are implemented in the GraphStageLogic
 * and exposed only through [[SlotContext]].
 */
@InternalApi
private[pool] object SlotState {
  sealed private[pool] /* to avoid warnings */ abstract class ConnectedState extends SlotState {
    def isConnected: Boolean = true

    // ignore embargo while still connected
    override def onNewConnectionEmbargo(ctx: SlotContext, embargoDuration: FiniteDuration): SlotState = this
  }
  sealed trait IdleState extends SlotState {
    final override def isIdle = true
  }
  sealed private[pool] /* to avoid warnings */ trait BusyState extends SlotState {
    // no HTTP pipelining: we could accept a new request when the request has been sent completely (or
    // even when the response has started to come in). However, that would mean the next request and response
    // are effectively blocked on the completion on the previous request and response. For this reason we
    // avoid accepting new connections in this slot while the previous request is still in progress: there might
    // be another slot available which can process the request with lower latency.
    final override def isIdle = false
    def ongoingRequest: RequestContext
    def waitingForEndOfRequestEntity: Boolean

    override def onShutdown(ctx: SlotContext): Unit = {
      // We would like to dispatch a failure here but responseOut might not be ready (or also already shutting down)
      // so we cannot do more than logging the problem here.

      ctx.warning(s"Ongoing request [{}] was dropped because pool is shutting down", ongoingRequest.request.debugString)

      super.onShutdown(ctx)
    }

    override def onConnectionAttemptFailed(ctx: SlotContext, cause: Throwable): SlotState = failOngoingRequest(ctx, "connection attempt failed", cause)

    override def onRequestEntityFailed(ctx: SlotContext, cause: Throwable): SlotState = failOngoingRequest(ctx, "request entity stream failed", cause)
    override def onConnectionCompleted(ctx: SlotContext): SlotState =
      // There's no good reason why the connection stream (i.e. the user-facing client Flow[HttpRequest, HttpResponse])
      // would complete during processing of a request.
      // One reason might be that failures on the TCP layer don't necessarily propagate through the stack as failures
      // because of the notorious cancel/failure propagation which can convert failures into completion.
      failOngoingRequest(ctx, "connection completed", new IllegalStateException("Connection was shutdown.") with NoStackTrace)

    override def onConnectionFailed(ctx: SlotContext, cause: Throwable): SlotState = failOngoingRequest(ctx, "connection failure", cause)

    private def failOngoingRequest(ctx: SlotContext, signal: String, cause: Throwable): SlotState = {
      ctx.debug("Ongoing request [{}] is failed because of [{}]: [{}]", ongoingRequest.request.debugString, signal, cause.getMessage)
      if (ongoingRequest.canBeRetried) { // push directly because it will be buffered internally
        ctx.dispatchResponseResult(ongoingRequest, Failure(cause))
        if (waitingForEndOfRequestEntity) WaitingForEndOfRequestEntity
        else Failed(cause)
      } else
        WaitingForResponseDispatch(ongoingRequest, Failure(cause), waitingForEndOfRequestEntity)
    }
  }

  private[pool] case class Embargoed(embargoDuration: FiniteDuration) extends SlotState {
    override def isConnected: Boolean = false
    override def isIdle: Boolean = false

    override val stateTimeout: Duration = newLevelTimeout()

    private def newLevelTimeout(): FiniteDuration =
      if (embargoDuration.toMillis > 0) {
        val minMillis = embargoDuration.toMillis
        val maxMillis = minMillis * 2
        ThreadLocalRandom.current().nextLong(minMillis, maxMillis).millis
      } else
        Duration.Zero

    override def onTimeout(ctx: SlotContext): SlotState = OutOfEmbargo

    override def onNewConnectionEmbargo(ctx: SlotContext, embargoDuration: FiniteDuration): SlotState =
      Embargoed(embargoDuration)
  }
  private[pool] trait UnconnectedState extends SlotState with IdleState {
    def isConnected: Boolean = false

    override def onPreConnect(ctx: SlotContext): SlotState = {
      ctx.openConnection()
      PreConnecting
    }

    override def onNewRequest(ctx: SlotContext, requestContext: RequestContext): SlotState = {
      ctx.openConnection()
      Connecting(requestContext)
    }

    override def onNewConnectionEmbargo(ctx: SlotContext, embargoDuration: FiniteDuration): SlotState =
      Embargoed(embargoDuration)
  }

  // a special case of `Unconnected` that will not be instantly re-embargoed
  private[pool] case object OutOfEmbargo extends UnconnectedState
  private[pool] case object Unconnected extends UnconnectedState

  private[pool] abstract class ShouldCloseConnectionState(val failure: Option[Throwable]) extends SlotState {
    override def isIdle: Boolean = false
    override def isConnected: Boolean = false
  }
  private[pool] case object ToBeClosed extends ShouldCloseConnectionState(None)
  private[pool] case class Failed(cause: Throwable) extends ShouldCloseConnectionState(Some(cause))

  private[pool] case object Idle extends ConnectedState with IdleState {
    override def onNewRequest(ctx: SlotContext, requestContext: RequestContext): SlotState =
      PushingRequestToConnection(requestContext)

    override def onConnectionCompleted(ctx: SlotContext): SlotState = ToBeClosed
    override def onConnectionFailed(ctx: SlotContext, cause: Throwable): SlotState = ToBeClosed
  }
  private[pool] final case class Connecting(ongoingRequest: RequestContext) extends ConnectedState with BusyState {
    val waitingForEndOfRequestEntity = false

    override def onConnectionAttemptSucceeded(ctx: SlotContext, outgoingConnection: Http.OutgoingConnection): SlotState = {
      ctx.debug("Slot connection was established")
      PushingRequestToConnection(ongoingRequest)
    }
    // connection failures are handled by BusyState implementations
  }

  private[pool] case object PreConnecting extends ConnectedState with IdleState {
    override def onConnectionAttemptSucceeded(ctx: SlotContext, outgoingConnection: Http.OutgoingConnection): SlotState = {
      ctx.debug("Slot connection was (pre-)established")
      Idle
    }
    override def onNewRequest(ctx: SlotContext, requestContext: RequestContext): SlotState =
      Connecting(requestContext)

    override def onConnectionAttemptFailed(ctx: SlotContext, cause: Throwable): SlotState = {
      // TODO: register failed connection attempt to be able to backoff (see https://github.com/akka/akka-http/issues/1391)
      onConnectionFailure(ctx, "connection attempt failed", cause)
    }
    override def onConnectionFailed(ctx: SlotContext, cause: Throwable): SlotState =
      onConnectionFailure(ctx, "connection failed", cause)

    override def onConnectionCompleted(ctx: SlotContext): SlotState =
      onConnectionFailure(
        ctx,
        "connection completed",
        new IllegalStateException("Unexpected connection closure") with NoStackTrace
      )

    private def onConnectionFailure(ctx: SlotContext, signal: String, cause: Throwable): SlotState = {
      ctx.debug("Connection was closed by [{}] while preconnecting because of [{}].", signal, cause.getMessage)
      Failed(cause)
    }
  }
  final case class PushingRequestToConnection(ongoingRequest: RequestContext) extends ConnectedState with BusyState {
    override def waitingForEndOfRequestEntity: Boolean = ???

    override def onRequestDispatched(ctx: SlotContext): SlotState =
      if (ongoingRequest.request.entity.isStrict) WaitingForResponse(ongoingRequest, waitingForEndOfRequestEntity = false)
      else WaitingForResponse(ongoingRequest, waitingForEndOfRequestEntity = true)
  }
  final case class WaitingForResponse(ongoingRequest: RequestContext, waitingForEndOfRequestEntity: Boolean) extends ConnectedState with BusyState {

    override def onRequestEntityCompleted(ctx: SlotContext): SlotState = {
      require(waitingForEndOfRequestEntity)
      WaitingForResponse(ongoingRequest, waitingForEndOfRequestEntity = false)
    }

    override def onResponseReceived(ctx: SlotContext, response: HttpResponse): SlotState = {
      ctx.debug(s"onResponseReceived in WaitingForResponse with $waitingForEndOfRequestEntity")
      WaitingForResponseDispatch(ongoingRequest, Success(response), waitingForEndOfRequestEntity)
    }

    // connection failures are handled by BusyState implementations
  }
  final case class WaitingForResponseDispatch(
    ongoingRequest:               RequestContext,
    result:                       Try[HttpResponse],
    waitingForEndOfRequestEntity: Boolean) extends ConnectedState with BusyWithResultAlreadyDetermined {

    override def onRequestEntityCompleted(ctx: SlotContext): SlotState = {
      require(waitingForEndOfRequestEntity)
      WaitingForResponseDispatch(ongoingRequest, result, waitingForEndOfRequestEntity = false)
    }

    /** Called when the response out port is ready to receive a further response (successful or failed) */
    override def onResponseDispatchable(ctx: SlotContext): SlotState = {
      ctx.dispatchResponseResult(ongoingRequest, result)

      result match {
        case Success(res)   => WaitingForResponseEntitySubscription(ongoingRequest, res, ctx.settings.responseEntitySubscriptionTimeout, waitingForEndOfRequestEntity)
        case Failure(cause) => Failed(cause)
      }
    }
  }

  private[pool] /* to avoid warnings */ trait BusyWithResultAlreadyDetermined extends ConnectedState with BusyState {
    override def onResponseEntityFailed(ctx: SlotContext, cause: Throwable): SlotState = {
      ctx.debug(s"Response entity for request [{}] failed with [{}]", ongoingRequest.request.debugString, cause.getMessage)
      // response must have already been dispatched, so don't try to dispatch a response
      Failed(cause)
    }

    // ignore since we already accepted the request
    override def onConnectionCompleted(ctx: SlotContext): SlotState = this
    override def onConnectionFailed(ctx: SlotContext, cause: Throwable): SlotState = this
    override def onRequestEntityFailed(ctx: SlotContext, cause: Throwable): SlotState = this
  }

  final case class WaitingForResponseEntitySubscription(
    ongoingRequest:  RequestContext,
    ongoingResponse: HttpResponse, override val stateTimeout: Duration, waitingForEndOfRequestEntity: Boolean) extends ConnectedState with BusyWithResultAlreadyDetermined {

    override def onRequestEntityCompleted(ctx: SlotContext): SlotState = {
      require(waitingForEndOfRequestEntity)
      WaitingForResponseEntitySubscription(ongoingRequest, ongoingResponse, stateTimeout, waitingForEndOfRequestEntity = false)
    }

    override def onResponseEntitySubscribed(ctx: SlotContext): SlotState =
      WaitingForEndOfResponseEntity(ongoingRequest, ongoingResponse, waitingForEndOfRequestEntity)

    override def onTimeout(ctx: SlotContext): SlotState = {
      val msg =
        s"Response entity was not subscribed after $stateTimeout. Make sure to read the response entity body or call `discardBytes()` on it. " +
          s"${ongoingRequest.request.debugString} -> ${ongoingResponse.debugString}"
      ctx.warning(msg) // FIXME: should still warn here?
      Failed(new TimeoutException(msg))
    }

  }
  final case class WaitingForEndOfResponseEntity(
    ongoingRequest:               RequestContext,
    ongoingResponse:              HttpResponse,
    waitingForEndOfRequestEntity: Boolean) extends ConnectedState with BusyWithResultAlreadyDetermined {

    override def onResponseEntityCompleted(ctx: SlotContext): SlotState =
      if (waitingForEndOfRequestEntity)
        WaitingForEndOfRequestEntity
      else if (ctx.willCloseAfter(ongoingResponse) || ctx.isConnectionClosed)
        ToBeClosed // when would ctx.isConnectionClose be true? what that mean that the connection has already failed before? do we need that state at all?
      else
        Idle

    override def onRequestEntityCompleted(ctx: SlotContext): SlotState = {
      require(waitingForEndOfRequestEntity)
      WaitingForEndOfResponseEntity(ongoingRequest, ongoingResponse, waitingForEndOfRequestEntity = false)
    }
  }
  final case object WaitingForEndOfRequestEntity extends ConnectedState {
    final override def isIdle = false

    override def onRequestEntityCompleted(ctx: SlotContext): SlotState =
      if (ctx.isConnectionClosed) ToBeClosed
      else Idle
    override def onRequestEntityFailed(ctx: SlotContext, cause: Throwable): SlotState =
      if (ctx.isConnectionClosed) ToBeClosed // ignore error here
      else Idle
    override def onConnectionCompleted(ctx: SlotContext): SlotState = ToBeClosed
    override def onConnectionFailed(ctx: SlotContext, cause: Throwable): SlotState = Failed(cause)
  }
}
