/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.client

import akka.Done
import akka.actor.{ Actor, ActorLogging, DeadLetterSuppression, Deploy, NoSerializationVerificationNeeded, Props }
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
 * Manages access to a host connection pool or rather: a sequence of pool incarnations.
 *
 * A host connection pool for a given [[HostConnectionPoolSetup]] is a running stream, whose outside interface is
 * provided by its [[PoolInterface]] actor. The actor accepts [[PoolInterface.PoolRequest]] messages
 * and completes their `responsePromise` whenever the respective response has been received (or an error occurred).
 *
 * The [[PoolMasterActor]] provides a layer of indirection between a [[PoolGateway]], which represents a pool,
 * and the [[PoolInterface]] instances which are created on-demand and stopped after an idle-timeout.
 *
 * Several [[PoolGateway]] objects may be mapped to the same pool if they have the same [[HostConnectionPoolSetup]]
 * and are marked as being shared. This is the case for example for gateways obtained through
 * [[HttpExt.cachedHostConnectionPool]]. Some other gateways are not shared, such as those obtained through
 * [[HttpExt.newHostConnectionPool]], and will have their dedicated restartable pool.
 *
 */
@InternalApi
private[http] final class PoolMasterActor extends Actor with ActorLogging {

  import PoolMasterActor._

  private[this] var poolStatus = Map[PoolGateway, PoolInterfaceStatus]()
  private[this] var poolInterfaces = Map[PoolInterface, PoolGateway]()

  /**
   * Start a new pool interface actor, register it in our maps, and watch its death. No actor should
   * currently exist for this pool.
   *
   * @param gateway the pool gateway this pool corresponds to
   * @param fm the materializer to use for this pool
   * @return the newly created actor ref
   */
  private[this] def startPoolInterface(gateway: PoolGateway)(implicit fm: Materializer): PoolInterface = {
    if (poolStatus.contains(gateway)) {
      throw new IllegalStateException(s"pool interface actor for $gateway already exists")
    }
    val interface = PoolInterface(gateway, context)
    poolStatus += gateway -> PoolInterfaceRunning(interface)
    poolInterfaces += interface -> gateway
    interface.whenShutdown.onComplete { reason => self ! HasBeenShutdown(interface, reason) }(context.dispatcher)
    interface
  }

  def receive = {

    // Start or restart a pool without sending it a request. This is used to ensure that
    // freshly created pools will be ready to serve requests immediately.
    case s @ StartPool(gateway, materializer) =>
      poolStatus.get(gateway) match {
        case Some(PoolInterfaceRunning(_)) =>
        case Some(PoolInterfaceShuttingDown(shutdownCompletedPromise)) =>
          // Pool is being shutdown. When this is done, start the pool again.
          shutdownCompletedPromise.future.onComplete(_ => self ! s)(context.dispatcher)
        case None =>
          startPoolInterface(gateway)(materializer)
      }

    // Send a request to a pool. If needed, the pool will be started or restarted.
    case s @ SendRequest(gateway, request, responsePromise, materializer) =>
      poolStatus.get(gateway) match {
        case Some(PoolInterfaceRunning(pool)) =>
          pool.request(request, responsePromise)
        case Some(PoolInterfaceShuttingDown(shutdownCompletedPromise)) =>
          // The request will be resent when the pool shutdown is complete (the first
          // request will recreate the pool).
          shutdownCompletedPromise.future.foreach(_ => self ! s)(context.dispatcher)
        case None =>
          startPoolInterface(gateway)(materializer).request(request, responsePromise)
      }

    // Shutdown a pool and signal its termination.
    case Shutdown(gateway, shutdownCompletedPromise) =>
      poolStatus.get(gateway).foreach {
        case PoolInterfaceRunning(pool) =>
          // Ask the pool to shutdown itself. Queued connections will be resent here
          // to this actor by the pool actor, they will be retried once the shutdown
          // has completed.
          val completed = pool.shutdown()(context.dispatcher)
          shutdownCompletedPromise.tryCompleteWith(completed.map(_ => Done)(ExecutionContexts.sameThreadExecutionContext))
          poolStatus += gateway -> PoolInterfaceShuttingDown(shutdownCompletedPromise)
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
      def track(remaining: Iterator[Future[Done]]): Unit =
        if (remaining.hasNext) remaining.next().onComplete(_ => track(remaining))
        else shutdownCompletedPromise.trySuccess(Done)
      track(poolStatus.keys.map(_.shutdown()).toIterator)

    case HasBeenShutdown(pool, reason) =>
      poolInterfaces.get(pool).foreach { gateway =>
        poolStatus.get(gateway) match {
          case Some(PoolInterfaceRunning(_)) =>
            import PoolInterface.ShutdownReason._
            reason match {
              case Success(IdleTimeout) =>
                log.debug("connection pool for {} was shut down because of idle timeout", PoolInterface.GatewayLogSource.genString(gateway))
              case Success(ShutdownRequested) =>
                log.debug("connection pool for {} has shut down as requested", PoolInterface.GatewayLogSource.genString(gateway))
              case Failure(ex) =>
                log.error(ex, "connection pool for {} has shut down unexpectedly", PoolInterface.GatewayLogSource.genString(gateway))
            }

          case Some(PoolInterfaceShuttingDown(shutdownCompletedPromise)) =>
            shutdownCompletedPromise.trySuccess(Done)
          case None =>
          // This will never happen as poolInterfaces and poolStatus are modified
          // together. If there is no status then there is no gateway to start with.
        }
        poolStatus -= gateway
        poolInterfaces -= pool
      }

    // Testing only.
    case PoolStatus(gateway, statusPromise) =>
      statusPromise.success(poolStatus.get(gateway))

    // Testing only.
    case PoolSize(sizePromise) =>
      sizePromise.success(poolStatus.size)
  }

}

private[http] object PoolMasterActor {

  val props = Props[PoolMasterActor].withDeploy(Deploy.local)

  sealed trait PoolInterfaceStatus
  final case class PoolInterfaceRunning(interface: PoolInterface) extends PoolInterfaceStatus
  final case class PoolInterfaceShuttingDown(shutdownCompletedPromise: Promise[Done]) extends PoolInterfaceStatus

  final case class StartPool(gateway: PoolGateway, materializer: Materializer) extends NoSerializationVerificationNeeded
  final case class SendRequest(gateway: PoolGateway, request: HttpRequest, responsePromise: Promise[HttpResponse], materializer: Materializer)
    extends NoSerializationVerificationNeeded
  final case class Shutdown(gateway: PoolGateway, shutdownCompletedPromise: Promise[Done]) extends NoSerializationVerificationNeeded with DeadLetterSuppression
  final case class ShutdownAll(shutdownCompletedPromise: Promise[Done]) extends NoSerializationVerificationNeeded with DeadLetterSuppression

  final case class HasBeenShutdown(interface: PoolInterface, reason: Try[ShutdownReason]) extends NoSerializationVerificationNeeded with DeadLetterSuppression

  final case class PoolStatus(gateway: PoolGateway, statusPromise: Promise[Option[PoolInterfaceStatus]]) extends NoSerializationVerificationNeeded
  final case class PoolSize(sizePromise: Promise[Int]) extends NoSerializationVerificationNeeded

}
