/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.client

import akka.Done
import akka.actor.{ Actor, ActorLogging, ActorRef, DeadLetterSuppression, Deploy, ExtendedActorSystem, NoSerializationVerificationNeeded, Props }
import akka.annotation.InternalApi
import akka.dispatch.ExecutionContexts
import akka.http.impl.engine.client.PoolInterface.ShutdownReason
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse }
import akka.stream.Materializer

import scala.concurrent.{ Future, Promise }
import scala.util.Failure
import scala.util.Success
import scala.util.Try

/**
 * INTERNAL API
 *
 * API for accessing the global pool master actor.
 */
@InternalApi
private[http] class PoolMaster(val ref: ActorRef) {
  import PoolMasterActor._

  /**
   * Send a request through the corresponding pool. If the pool is not running, it will be started
   * automatically. If it is shutting down, it will restart as soon as the shutdown operation is
   * complete and serve this request.
   *
   * @param request the request
   * @return the response
   */
  def dispatchRequest(poolId: PoolId, request: HttpRequest)(implicit fm: Materializer): Future[HttpResponse] = {
    val responsePromise = Promise[HttpResponse]()
    ref ! SendRequest(poolId, request, responsePromise, fm)
    responsePromise.future
  }
  /**
   * Start the corresponding pool to make it ready to serve requests. If the pool is already started,
   * this does nothing. If it is being shutdown, it will restart as soon as the shutdown operation
   * is complete.
   *
   * @return the gateway itself
   */
  def startPool(poolId: PoolId)(implicit fm: Materializer): Unit = ref ! StartPool(poolId, fm)

  /**
   * Shutdown the corresponding pool and signal its termination. If the pool is not running or is
   * being shutting down, this does nothing.
   *
   * The shutdown will wait for all ongoing requests to be completed.
   *
   * @return a Future completed when the pool has been shutdown.
   */
  def shutdown(poolId: PoolId): Future[Done] = {
    val shutdownCompletedPromise = Promise[Done]()
    ref ! Shutdown(poolId, shutdownCompletedPromise)
    shutdownCompletedPromise.future
  }

  /**
   * Triggers an orderly shutdown of all host connections pools currently maintained by the [[akka.actor.ActorSystem]].
   * The returned future is completed when all pools that were live at the time of this method call
   * have completed their shutdown process.
   *
   * If existing pool client flows are re-used or new ones materialized concurrently with or after this
   * method call the respective connection pools will be restarted and not contribute to the returned future.
   */
  def shutdownAll(): Future[Done] = {
    val shutdownCompletedPromise = Promise[Done]()
    ref ! ShutdownAll(shutdownCompletedPromise)
    shutdownCompletedPromise.future
  }

  /**
   * For testing only
   */
  def poolSize(): Future[Int] = {
    val sizePromise = Promise[Int]()
    ref ! PoolSize(sizePromise)
    sizePromise.future
  }
}
private[http] object PoolMaster {
  def apply()(implicit system: ExtendedActorSystem): PoolMaster = new PoolMaster(system.systemActorOf(PoolMasterActor.props, "pool-master"))
}

/**
 * INTERNAL API
 *
 * Manages access to a host connection pool or rather: a sequence of pool incarnations.
 *
 * A host connection pool for a given [[HostConnectionPoolSetup]] is a running stream, whose outside interface is
 * provided by its [[PoolInterface]] actor. The actor accepts [[PoolInterface.PoolRequest]] messages
 * and completes their `responsePromise` whenever the respective response has been received (or an error occurred).
 *
 * The [[PoolMasterActor]] provides a layer of indirection between a [[PoolId]], which represents a pool,
 * and the [[PoolInterface]] instances which are created on-demand and stopped after an idle-timeout.
 *
 * Several [[PoolId]] objects may be mapped to the same pool if they have the same [[HostConnectionPoolSetup]]
 * and are marked as being shared. This is the case for example for gateways obtained through
 * [[HttpExt.cachedHostConnectionPool]]. Some other gateways are not shared, such as those obtained through
 * [[HttpExt.newHostConnectionPool]], and will have their dedicated restartable pool.
 *
 */
@InternalApi
private[http] final class PoolMasterActor extends Actor with ActorLogging {
  private[this] val thisMaster: PoolMaster = new PoolMaster(self)

  import PoolMasterActor._

  private[this] var statusById = Map[PoolId, PoolInterfaceStatus]()
  private[this] var idByPool = Map[PoolInterface, PoolId]()

  /**
   * Start a new pool interface actor, register it in our maps, and watch its death. No actor should
   * currently exist for this pool.
   *
   * @param poolId the pool id this pool corresponds to
   * @param fm the materializer to use for this pool
   * @return the newly created actor ref
   */
  private[this] def startPoolInterface(poolId: PoolId)(implicit fm: Materializer): PoolInterface = {
    if (statusById.contains(poolId)) {
      throw new IllegalStateException(s"pool interface actor for $poolId already exists")
    }
    val interface = PoolInterface(poolId, context, thisMaster)
    statusById += poolId -> PoolInterfaceRunning(interface)
    idByPool += interface -> poolId
    interface.whenShutdown.onComplete { reason => self ! HasBeenShutdown(interface, reason) }(context.dispatcher)
    interface
  }

  def receive = {

    // Start or restart a pool without sending it a request. This is used to ensure that
    // freshly created pools will be ready to serve requests immediately.
    case s @ StartPool(poolId, materializer) =>
      statusById.get(poolId) match {
        case Some(PoolInterfaceRunning(_)) =>
        case Some(PoolInterfaceShuttingDown(shutdownCompletedPromise)) =>
          // Pool is being shutdown. When this is done, start the pool again.
          shutdownCompletedPromise.future.onComplete(_ => self ! s)(context.dispatcher)
        case None =>
          startPoolInterface(poolId)(materializer)
      }

    // Send a request to a pool. If needed, the pool will be started or restarted.
    case s @ SendRequest(poolId, request, responsePromise, materializer) =>
      statusById.get(poolId) match {
        case Some(PoolInterfaceRunning(pool)) =>
          pool.request(request, responsePromise)
        case Some(PoolInterfaceShuttingDown(shutdownCompletedPromise)) =>
          // The request will be resent when the pool shutdown is complete (the first
          // request will recreate the pool).
          shutdownCompletedPromise.future.foreach(_ => self ! s)(context.dispatcher)
        case None =>
          startPoolInterface(poolId)(materializer).request(request, responsePromise)
      }

    // Shutdown a pool and signal its termination.
    case Shutdown(poolId, shutdownCompletedPromise) =>
      statusById.get(poolId).foreach {
        case PoolInterfaceRunning(pool) =>
          // Ask the pool to shutdown itself. Queued connections will be resent here
          // to this actor by the pool actor, they will be retried once the shutdown
          // has completed.
          val completed = pool.shutdown()(context.dispatcher)
          shutdownCompletedPromise.tryCompleteWith(completed.map(_ => Done)(ExecutionContexts.sameThreadExecutionContext))
          statusById += poolId -> PoolInterfaceShuttingDown(shutdownCompletedPromise)
        case PoolInterfaceShuttingDown(formerPromise) =>
          // Pool is already shutting down, mirror the existing promise.
          shutdownCompletedPromise.tryCompleteWith(formerPromise.future)
        case _ =>
          // Pool does not exist, shutdown is not needed.
          shutdownCompletedPromise.trySuccess(Done)
      }

    // Shutdown all known pools and signal their termination.
    case ShutdownAll(shutdownCompletedPromise) =>
      import context.dispatcher
      // FIXME: shutdown pools directly without going through message, https://github.com/akka/akka-http/issues/3184
      Future.traverse(statusById.keys)(thisMaster.shutdown)
        .onComplete(_ => shutdownCompletedPromise.trySuccess(Done))

    case HasBeenShutdown(pool, reason) =>
      idByPool.get(pool).foreach { poolId =>
        statusById.get(poolId) match {
          case Some(PoolInterfaceRunning(_)) =>
            import PoolInterface.ShutdownReason._
            reason match {
              case Success(IdleTimeout) =>
                log.debug("connection pool for {} was shut down because of idle timeout", PoolInterface.PoolLogSource.genString(poolId))
              case Success(ShutdownRequested) =>
                log.debug("connection pool for {} has shut down as requested", PoolInterface.PoolLogSource.genString(poolId))
              case Failure(ex) =>
                log.error(ex, "connection pool for {} has shut down unexpectedly", PoolInterface.PoolLogSource.genString(poolId))
            }

          case Some(PoolInterfaceShuttingDown(shutdownCompletedPromise)) =>
            shutdownCompletedPromise.trySuccess(Done)
          case None =>
          // This will never happen as poolInterfaces and poolStatus are modified
          // together. If there is no status then there is no poolId to start with.
        }
        statusById -= poolId
        idByPool -= pool
      }

    // Testing only.
    case PoolStatus(poolId, statusPromise) =>
      statusPromise.success(statusById.get(poolId))

    // Testing only.
    case PoolSize(sizePromise) =>
      sizePromise.success(statusById.size)
  }

}

private[http] object PoolMasterActor {

  val props = Props[PoolMasterActor]().withDeploy(Deploy.local)

  sealed trait PoolInterfaceStatus
  final case class PoolInterfaceRunning(interface: PoolInterface) extends PoolInterfaceStatus
  final case class PoolInterfaceShuttingDown(shutdownCompletedPromise: Promise[Done]) extends PoolInterfaceStatus

  final case class StartPool(poolId: PoolId, materializer: Materializer) extends NoSerializationVerificationNeeded
  final case class SendRequest(poolId: PoolId, request: HttpRequest, responsePromise: Promise[HttpResponse], materializer: Materializer)
    extends NoSerializationVerificationNeeded
  final case class Shutdown(poolId: PoolId, shutdownCompletedPromise: Promise[Done]) extends NoSerializationVerificationNeeded with DeadLetterSuppression
  final case class ShutdownAll(shutdownCompletedPromise: Promise[Done]) extends NoSerializationVerificationNeeded with DeadLetterSuppression

  final case class HasBeenShutdown(interface: PoolInterface, reason: Try[ShutdownReason]) extends NoSerializationVerificationNeeded with DeadLetterSuppression

  final case class PoolStatus(poolId: PoolId, statusPromise: Promise[Option[PoolInterfaceStatus]]) extends NoSerializationVerificationNeeded
  final case class PoolSize(sizePromise: Promise[Int]) extends NoSerializationVerificationNeeded

}
