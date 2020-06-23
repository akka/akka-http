/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.client

import akka.Done
import akka.NotUsed
import akka.actor._
import akka.annotation.InternalStableApi
import akka.event.{ LogSource, Logging, LoggingAdapter }
import akka.http.impl.engine.client.PoolFlow._
import akka.http.impl.engine.client.pool.NewHostConnectionPool
import akka.http.impl.util._
import akka.http.scaladsl.model._
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import akka.stream.Attributes
import akka.stream.FlowShape
import akka.stream.Inlet
import akka.stream.Outlet
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.Flow
import akka.stream.stage.GraphStageLogic
import akka.stream.stage.GraphStageWithMaterializedValue
import akka.stream.stage.InHandler
import akka.stream.stage.OutHandler
import akka.stream.stage.TimerGraphStageLogic
import akka.stream.{ BufferOverflowException, Materializer }

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.util.{ Failure, Success, Try }

/**
 * The pool interface is a push style interface to a pool of connections against a single host.
 */
private[http] trait PoolInterface {
  /**
   * Submit request to pool. After completion the pool will complete the promise with the response.
   * If the queue in front of the pool is full, the promise will be failed with a BufferOverflowException.
   */
  def request(request: HttpRequest, responsePromise: Promise[HttpResponse]): Unit
  def shutdown()(implicit ec: ExecutionContext): Future[PoolInterface.ShutdownReason]
  def whenShutdown: Future[PoolInterface.ShutdownReason]
}

private[http] object PoolInterface {
  private[http] sealed trait ShutdownReason
  private[http] object ShutdownReason {
    case object ShutdownRequested extends ShutdownReason
    case object IdleTimeout extends ShutdownReason
  }

  def apply(poolId: PoolId, parent: ActorRefFactory, master: PoolMaster)(implicit fm: Materializer): PoolInterface = {
    import poolId.hcps
    import hcps._
    import setup.{ connectionContext, settings }
    implicit val system = fm.asInstanceOf[ActorMaterializer].system
    val log: LoggingAdapter = Logging(system, poolId)(PoolLogSource)

    log.debug("Creating pool.")

    val connectionFlow =
      Http().outgoingConnectionUsingContext(host, port, connectionContext, settings.connectionSettings, setup.log)

    val poolFlow = NewHostConnectionPool(connectionFlow, settings, log).named("PoolFlow")

    val bufferIfNeeded: Flow[RequestContext, RequestContext, NotUsed] = {
      val targetBufferSize = settings.maxOpenRequests - settings.maxConnections
      if (targetBufferSize > 0) Flow[RequestContext].buffer(targetBufferSize, OverflowStrategy.backpressure)
      else Flow[RequestContext]
    }

    Flow.fromGraph(new PoolInterfaceStage(poolId, master, log))
      .via(bufferIfNeeded)
      .join(poolFlow)
      .run()
  }

  private val IdleTimeout = "idle-timeout"

  class PoolInterfaceStage(poolId: PoolId, master: PoolMaster, log: LoggingAdapter) extends GraphStageWithMaterializedValue[FlowShape[ResponseContext, RequestContext], PoolInterface] {
    private val requestOut = Outlet[RequestContext]("PoolInterface.requestOut")
    private val responseIn = Inlet[ResponseContext]("PoolInterface.responseIn")
    override def shape = FlowShape(responseIn, requestOut)

    override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, PoolInterface) =
      throw new IllegalStateException("Should not be called")
    override def createLogicAndMaterializedValue(inheritedAttributes: Attributes, _materializer: Materializer): (GraphStageLogic, PoolInterface) = {
      import _materializer.executionContext
      val logic = new Logic(poolId, shape, master, requestOut, responseIn, log)
      (logic, logic)
    }
  }

  @InternalStableApi // name `Logic` and annotated methods
  private class Logic(poolId: PoolId, shape: FlowShape[ResponseContext, RequestContext], master: PoolMaster, requestOut: Outlet[RequestContext], responseIn: Inlet[ResponseContext],
                      log: LoggingAdapter)(implicit executionContext: ExecutionContext) extends TimerGraphStageLogic(shape) with PoolInterface with InHandler with OutHandler {
    private[this] val PoolOverflowException = new BufferOverflowException( // stack trace cannot be prevented here because `BufferOverflowException` is final
      s"Exceeded configured max-open-requests value of [${poolId.hcps.setup.settings.maxOpenRequests}]. This means that the request queue of this pool (${poolId.hcps}) " +
        s"has completely filled up because the pool currently does not process requests fast enough to handle the incoming request load. " +
        "Please retry the request later. See http://doc.akka.io/docs/akka-http/current/scala/http/client-side/pool-overflow.html for " +
        "more information.")

    val hcps = poolId.hcps
    val idleTimeout = hcps.setup.settings.idleTimeout

    val shutdownPromise = Promise[ShutdownReason]()
    def shuttingDown: Boolean = shuttingDownReason.isDefined
    var shuttingDownReason: Option[ShutdownReason] = None
    var remainingRequested = 0

    setHandlers(responseIn, requestOut, this)

    override def preStart(): Unit = {
      onInit(poolId)
      pull(responseIn)
      resetIdleTimer()
    }

    override protected def onTimer(timerKey: Any): Unit = {
      log.debug(s"Pool shutting down because akka.http.host-connection-pool.idle-timeout triggered after $idleTimeout.")
      requestShutdown(ShutdownReason.IdleTimeout)
    }

    override def onPush(): Unit = {
      // incoming response
      val ResponseContext(rc, response0) = grab(responseIn)
      val ctx = onResponse(rc.request, response0)
      val response1 =
        response0 match {
          case Success(r @ HttpResponse(_, _, entity, _)) if !entity.isStrict =>
            val (newEntity, termination) = StreamUtils.transformEntityStream(entity, StreamUtils.CaptureTerminationOp)
            termination.onComplete(_ => responseCompletedCallback.invoke(Done))
            Success(r.withEntity(newEntity))
          case Success(response) =>
            remainingRequested -= 1
            Success(response)
          case Failure(_) =>
            remainingRequested -= 1
            response0
        }
      rc.responsePromise.complete(response1)
      onResponseComplete(ctx)
      pull(responseIn)

      afterRequestFinished()
    }
    override def onPull(): Unit = () // pool/buffer can now handle new request, checked with isAvailable below

    val responseCompletedCallback = getAsyncCallback[Done] { _ => remainingRequested -= 1; afterRequestFinished() }
    val requestCallback = getAsyncCallback[(HttpRequest, Promise[HttpResponse])] {
      case (request, responsePromise) =>
        if (isAvailable(requestOut)) {
          log.debug(s"Dispatching request [${request.debugString}] to pool")
          val scheme = Uri.httpScheme(hcps.setup.connectionContext.isSecure)
          val hostHeader = headers.Host(hcps.host, Uri.normalizePort(hcps.port, scheme))
          val effectiveRequest =
            onDispatch(
              request
                .withUri(request.uri.toHttpRequestTargetOriginForm)
                .withDefaultHeaders(hostHeader)
            )
          val retries = if (request.method.isIdempotent) hcps.setup.settings.maxRetries else 0
          remainingRequested += 1
          resetIdleTimer()
          push(requestOut, RequestContext(effectiveRequest, responsePromise, retries))
        } else {
          log.debug(s"Could not dispatch request [${request.debugString}] because buffer is full")
          responsePromise.tryFailure(PoolOverflowException)
        }
    }
    val shutdownCallback = getAsyncCallback[Unit] { _ => requestShutdown(ShutdownReason.ShutdownRequested) }

    def afterRequestFinished(): Unit = {
      shutdownIfRequestedAndPossible()
      resetIdleTimer()
    }

    def requestShutdown(reason: ShutdownReason): Unit = {
      shuttingDownReason = Some(reason)
      shutdownIfRequestedAndPossible()
    }
    def shutdownIfRequestedAndPossible(): Unit =
      if (shuttingDown) {
        if (remainingRequested == 0) {
          log.debug("Pool is now shutting down as requested.")
          shutdownPromise.trySuccess(shuttingDownReason.get)
          completeStage()
        } else
          log.debug(s"Pool is shutting down after waiting for [$remainingRequested] outstanding requests.")
      }

    def resetIdleTimer(): Unit = {
      cancelTimer(IdleTimeout)

      if (shouldStopOnIdle) scheduleOnce(IdleTimeout, idleTimeout.asInstanceOf[FiniteDuration])
    }
    def shouldStopOnIdle: Boolean =
      !shuttingDown && remainingRequested == 0 && idleTimeout.isFinite && hcps.setup.settings.minConnections == 0

    override def onUpstreamFailure(ex: Throwable): Unit = shutdownPromise.tryFailure(ex)
    override def postStop(): Unit = shutdownPromise.tryFailure(new IllegalStateException("Pool shutdown unexpectedly"))

    // PoolInterface implementations
    override def request(request: HttpRequest, responsePromise: Promise[HttpResponse]): Unit =
      requestCallback.invokeWithFeedback((request, responsePromise)).failed.foreach { _ =>
        log.debug("Request was sent to pool which was already closed, retrying through the master to create new pool instance")
        responsePromise.tryCompleteWith(master.dispatchRequest(poolId, request)(materializer))
      }
    override def shutdown()(implicit ec: ExecutionContext): Future[ShutdownReason] = {
      shutdownCallback.invoke(())
      whenShutdown
    }
    override def whenShutdown: Future[ShutdownReason] = shutdownPromise.future

    @InternalStableApi
    def onInit(poolId: PoolId): Unit = ()
    @InternalStableApi
    def onDispatch(request: HttpRequest): HttpRequest = request
    @InternalStableApi
    def onResponse(request: HttpRequest, response: Try[HttpResponse]): Any = None
    @InternalStableApi
    def onResponseComplete(any: Any): Unit = ()
  }

  /**
   * LogSource for pool instances
   *
   * Using this LogSource allows us to set the log class to `PoolId` and the log source string
   * to a descriptive name that describes a particular pool instance.
   */
  private[http] val PoolLogSource: LogSource[PoolId] =
    new LogSource[PoolId] {
      def genString(poolId: PoolId): String = {
        val scheme = if (poolId.hcps.setup.connectionContext.isSecure) "https" else "http"
        s"Pool(${poolId.usage.name}->$scheme://${poolId.hcps.host}:${poolId.hcps.port})"
      }
      override def genString(poolId: PoolId, system: ActorSystem): String = s"${system.name}/${genString(poolId)}"

      override def getClazz(t: PoolId): Class[_] = classOf[PoolId]
    }
}
