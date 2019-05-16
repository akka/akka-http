/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.server

import java.util.concurrent.atomic.AtomicReference

import akka.actor.ActorSystem
import akka.annotation.InternalApi
import akka.event.LoggingAdapter
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.{ HttpConnectionTerminated, HttpServerTerminated, HttpTerminated }
import akka.http.scaladsl.model.headers.Connection
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse }
import akka.http.scaladsl.settings.ServerSettings
import akka.stream._
import akka.stream.scaladsl.BidiFlow
import akka.stream.stage._
import akka.util.PrettyDuration

import scala.annotation.tailrec
import scala.concurrent.duration.{ Deadline, FiniteDuration }
import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.util.{ Failure, Success }

/**
 * INTERNAL API: Used to start the termination process of an Akka HTTP server.
 */
// "Hasta la vista, baby."
@InternalApi
private[http] abstract class ServerTerminator {
  /**
   * Initiate the termination sequence of this server.
   */
  def terminate(deadline: FiniteDuration)(implicit ex: ExecutionContext): Future[HttpTerminated]
}

/** INTERNAL API */
@InternalApi
private[http] object MasterServerTerminator {
  sealed trait State
  final case class AliveConnectionTerminators(ts: Set[ServerTerminator]) extends State
  final case class Terminating(deadline: Deadline) extends State
}

/** INTERNAL API: Collects signals from per-connection terminators and manages the termination process kickoff */
@InternalApi
private[http] final class MasterServerTerminator(log: LoggingAdapter) extends ServerTerminator {
  import MasterServerTerminator._

  // if we get a termination signal from the outer (user-land) it has to send this signal to all existing connection
  // terminators such that they can initiate the termination (draining, failing) of their respective connections.

  private val terminators = new AtomicReference[MasterServerTerminator.State](MasterServerTerminator.AliveConnectionTerminators(Set.empty))
  private val termination = Promise[HttpTerminated]()

  /**
   * Registers an per-connection terminator.
   * Once the master terminator gets the termination signal,
   * it will delegate the signal to all registered terminators (existing connections).
   *
   * @return true if registered successfully and not terminating, false if termination in-flight
   *         (and the terminators `terminate()` will be invoked in that case automatically)
   */
  @tailrec def registerConnection(terminator: ServerTerminator)(implicit ec: ExecutionContext): Boolean = {
    terminators.get() match {
      case v @ AliveConnectionTerminators(ts) =>
        terminators.compareAndSet(v, v.copy(ts = ts + terminator)) ||
          registerConnection(terminator) // retry

      case Terminating(deadline) =>
        terminator.terminate(deadline.timeLeft)(ec)
        false // termination is in progress already, we did not register but immediately issue termination
    }
  }

  /**
   * Removes a previously registered per-connection terminator.
   * Terminators must remove themselves like this once their respective connection is closed,
   * otherwise they would leak and remain in the set indefinitely.
   *
   * @return true if the terminator has been successfully removed.
   */
  @tailrec def removeConnection(terminator: ServerTerminator): Unit = {
    terminators.get() match {
      case v @ AliveConnectionTerminators(ts) =>
        if (!terminators.compareAndSet(v, v.copy(ts = ts - terminator)))
          removeConnection(terminator) // retry

      case _: Terminating =>
      // the `terminator` that we are being called with can only be one that already was registered,
      // due to the register call happening during materialization, and the remove call happening during
      // connection stream completion. Since we are in Terminating state, this means `terminate()` was called,
      // and all existing terminators were invoked to `terminate()`. So if this is an existing one, we must not call
      // terminate on it, as it would be the 2nd invocation.
    }
  }

  // If a connection attempts to register once termination has started, it will immediately be rejected (though
  // since termination also implies unbinding such new connections should not really happen).
  def terminate(timeout: FiniteDuration)(implicit ex: ExecutionContext): Future[HttpTerminated] = {
    terminators.get() match {
      case v @ AliveConnectionTerminators(emptyTs) if emptyTs.isEmpty =>
        if (terminators.compareAndSet(v, Terminating(timeout.fromNow))) {
          // no connections exist to be terminated, and if some arrive after us, they will see the Terminating and terminate
          termination.trySuccess(HttpServerTerminated)
          termination.future
        } else terminate(timeout)(ex) // retry

      case v @ AliveConnectionTerminators(ts) =>
        if (terminators.compareAndSet(v, Terminating(timeout.fromNow))) {
          // cause the termination for all connections
          val connectionsTerminated = Future.sequence(ts.map { t =>
            // termination in general always succeeds, but we make sure here in order
            // to not accidentally short-circuit terminating the other connection-terminators -- all must be terminated
            t.terminate(timeout).recover {
              case ex =>
                log.warning("Ignoring termination failure of {}, failure was: {}", t, ex.getMessage)
                HttpServerTerminated
            }
          })
          val serverTerminated = connectionsTerminated.map(_ => HttpServerTerminated)
          termination.completeWith(serverTerminated)
          termination.future
        } else terminate(timeout)(ex) // retry

      case Terminating(existingDeadline) =>
        log.warning(s"Issued terminate($timeout) while termination is in progress already (with deadline: time left: ${PrettyDuration.format(existingDeadline.timeLeft)}")
        termination.future
    }
  }
}

/**
 * INTERNAL API
 *
 * Used to fail when terminating connections forcefully at end of termination deadline.
 * Not intended to be recovered from or caught by user error handlers.
 */
@InternalApi
private[http] final class ServerTerminationDeadlineReached()
  extends RuntimeException("Server termination deadline reached, shutting down all connections and terminating server...")

object GracefulTerminatorStage {
  def apply(system: ActorSystem, serverSettings: ServerSettings): BidiFlow[HttpResponse, HttpResponse, HttpRequest, HttpRequest, ServerTerminator] = {
    val stage = new GracefulTerminatorStage(serverSettings)
    BidiFlow.fromGraph(stage)
  }
}

/**
 * INTERNAL API: See detailed docs termination process on [[akka.http.scaladsl.Http.ServerBinding]].
 *
 * Stage shape diagram:
 *
 * {{{
 *                      +---+
 * fromNet Request   -> | G | -> toUser Request
 *                      | T |
 * toNet   Response  <- | S | <- fromUser Response
 *                      +---+
 * }}}
 */
@InternalApi
private[http] final class GracefulTerminatorStage(settings: ServerSettings)
  extends GraphStageWithMaterializedValue[BidiShape[HttpResponse, HttpResponse, HttpRequest, HttpRequest], ServerTerminator] {

  val fromNet: Inlet[HttpRequest] = Inlet("netIn")
  val toUser: Outlet[HttpRequest] = Outlet("userOut")

  val fromUser: Inlet[HttpResponse] = Inlet("userIn")
  val toNet: Outlet[HttpResponse] = Outlet("netOut")
  override def shape = BidiShape(fromUser, toNet, fromNet, toUser)

  final val TerminationDeadlineTimerKey = "TerminationDeadlineTimerKey"

  final class ConnectionTerminator(triggerTermination: Promise[FiniteDuration => Future[HttpTerminated]]) extends ServerTerminator {
    override def terminate(deadline: FiniteDuration)(implicit ec: ExecutionContext): Future[HttpTerminated] = {
      triggerTermination.future.flatMap(callback => {
        callback(deadline)
      })
    }
  }

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, ServerTerminator) = {
    val triggerTermination = Promise[FiniteDuration => Future[HttpTerminated]]() // result here means termination of this connection has completed

    // responsible for terminating this connection
    val selfTerminator = new ConnectionTerminator(triggerTermination)

    val logic = new TimerGraphStageLogic(shape) with StageLogging {
      override protected def logSource: Class[_] = classOf[GracefulTerminatorStage]

      // this promise will be completed once our termination is complete;
      // e.g. we replied with "go away" to pending requests, and no new ones were incoming etc
      val terminationOfConnectionDone = Promise[HttpConnectionTerminated]()

      // error callback, in case an asynchronous operation needs to pipe back a failure back to this stage
      // this could happen during draining of incoming http requests during termination phase for example
      lazy val failureCallback: AsyncCallback[Throwable] = getAsyncCallback((ex: Throwable) => failStage(ex))

      // true, if a request was delivered to the user-handler, and no response was sent yet
      // in that case, if the termination timeout triggers, we will need to render a synthetic response to the client.
      var pendingUserHandlerResponse: Boolean = false
      var pendingTerminationResponse: Boolean = false

      override def preStart(): Unit = {
        val terminateSignal = getAsyncCallback[FiniteDuration] { deadline =>
          log.debug("[terminator] Initializing termination of server, deadline: {}", PrettyDuration.format(deadline))
          installTerminationHandlers(deadline.fromNow)

          scheduleOnce(TerminationDeadlineTimerKey, deadline)
        }

        // force initialization of lazy val:
        val _ = failureCallback

        // this way we expose the termination signal invocation to the external world, in a type safe fashion
        triggerTermination.success { d =>
          terminateSignal.invoke(d)
          terminationOfConnectionDone.future // will be completed once termination has completed (in postStop)
        }
      }

      setHandler(fromUser, new InHandler {
        override def onPush(): Unit = {
          val response = grab(fromUser)
          pendingUserHandlerResponse = false
          push(toNet, response)
        }

        override def onUpstreamFinish(): Unit = {
          // don't finish the whole bidi stage, just propagate the completion:
          complete(toNet)
        }
      })
      setHandler(toUser, new OutHandler {
        override def onPull(): Unit = {
          pull(fromNet)
        }
      })
      setHandler(fromNet, new InHandler {
        override def onPush(): Unit = {
          val request = grab(fromNet)

          pendingUserHandlerResponse = true
          push(toUser, request)
        }

        override def onUpstreamFinish(): Unit = {
          // don't finish the whole bidi stage, just propagate the completion:
          complete(toUser)
        }
      })
      setHandler(toNet, new OutHandler {
        override def onPull(): Unit = {
          pull(fromUser)
        }
      })

      def installTerminationHandlers(deadline: Deadline): Unit = {
        // when no inflight requests, complete stage right away
        if (!pendingUserHandlerResponse) completeStage()

        setHandler(fromUser, new InHandler {
          override def onPush(): Unit = {
            val overdue = deadline.isOverdue()
            val response =
              if (overdue) {
                log.warning("Terminating server ({}), discarding user reply since arrived after deadline expiration", formatTimeLeft(deadline))
                settings.terminationDeadlineExceededResponse
              } else grab(fromUser)

            pendingUserHandlerResponse = false

            // send response to pending in-flight request with Connection: close, and complete stage
            emit(toNet, response.withHeaders(Connection("close")), () => completeStage())
          }
        })

        // once termination deadline hits, we stop pulling from network
        setHandler(toUser, new OutHandler {
          override def onPull(): Unit = {
            // if (deadline.hasTimeLeft()) // we pull always as we want to reply errors to everyone
            pull(fromNet)
          }
        })

        setHandler(fromNet, new InHandler {
          override def onPush(): Unit = {
            val request = grab(fromNet)
            log.warning(
              "Terminating server ({}), attempting to send termination reply to incoming [{} {}]",
              formatTimeLeft(deadline), request.method, request.uri.path)

            // on purpose discard all incoming bytes for requests
            // could discard with the deadline.timeLeft completion timeout, but not necessarily needed
            request.entity.discardBytes()(interpreter.subFusingMaterializer).future.onComplete {
              case Success(_) => // ignore
              case Failure(ex) =>
                // we do want to cause this failure to fail the termination eagerly
                failureCallback.invoke(ex)
            }(interpreter.materializer.executionContext)

            // we can reply right away with an termination response since user handler will never emit a response anymore
            push(toNet, settings.terminationDeadlineExceededResponse.withHeaders(Connection("close")))
            completeStage()
          }
        })

        // we continue pulling from user, to make sure we'd get the "final user reply" that may be sent during termination
        setHandler(toNet, new OutHandler {
          override def onPull(): Unit = {
            if (pendingUserHandlerResponse) {
              if (isAvailable(fromUser)) pull(fromUser)
            } else if (pendingTerminationResponse) {
              pendingTerminationResponse = false
              push(toNet, settings.terminationDeadlineExceededResponse)
            }
          }
        })
      }

      override def postStop(): Unit = {
        terminationOfConnectionDone.success(Http.HttpConnectionTerminated)
      }

      override protected def onTimer(timerKey: Any): Unit = timerKey match {
        case TerminationDeadlineTimerKey =>
          val ex = new ServerTerminationDeadlineReached
          if (pendingUserHandlerResponse) {
            // sending the reply here is a "nice to try", but the stage failure will likely overtake it and terminate the connection first
            emit(toNet, settings.terminationDeadlineExceededResponse, () => failStage(ex))
          } else {
            failStage(ex)
          }

        case unexpected =>
          // should not happen
          throw new IllegalArgumentException(s"Unexpected timer key [$unexpected] in ${getClass.getName}!")
      }

      def formatTimeLeft(d: Deadline): String = {
        val left = d.timeLeft
        if (left.toMillis < 0) "deadline exceeded"
        else PrettyDuration.format(left) + " remaining"
      }
    }

    logic -> selfTerminator
  }
}
