/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl

import java.net.InetSocketAddress
import java.util.concurrent.{ CompletionStage, TimeUnit }

import akka.Done
import akka.annotation.DoNotInherit
import akka.dispatch.ExecutionContexts
import akka.util.JavaDurationConverters

import scala.compat.java8.FutureConverters._
import scala.compat.java8.FutureConverters._
import scala.concurrent.duration.FiniteDuration
import JavaDurationConverters._

/**
 * Represents a prospective HTTP server binding.
 */
class ServerBinding private[http] (delegate: akka.http.scaladsl.Http.ServerBinding) {
  /**
   * The local address of the endpoint bound by the materialization of the `connections` [[akka.stream.javadsl.Source]].
   */
  def localAddress: InetSocketAddress = delegate.localAddress

  /**
   * Asynchronously triggers the unbinding of the port that was bound by the materialization of the `connections`
   * [[akka.stream.javadsl.Source]]
   *
   * The produced [[java.util.concurrent.CompletionStage]] is fulfilled when the unbinding has been completed.
   */
  def unbind(): CompletionStage[Done] =
    delegate.unbind().toJava

  /**
   * Triggers "graceful" termination request being handled on this connection.
   *
   * Termination works as follows:
   *
   * 1) Unbind:
   * - the server port is unbound; no new connections will be accepted.
   *
   * 1.5) Immediately the ServerBinding `whenTerminationSignalIssued` future is completed.
   * This can be used to signal parts of the application that the http server is shutting down and they should clean up as well.
   * Note also that for more advanced shut down scenarios you may want to use the Coordinated Shutdown capabilities of Akka.
   *
   * 2) if a connection has no "in-flight" request, it is terminated immediately
   *
   * 3) Handle in-flight request:
   * - if a request is "in-flight" (being handled by user code), it is given `hardDeadline` time to complete,
   *   - if user code emits a response within the timeout, then this response is sent to the client with a `Connection: close` header and the connection is closed.
   *     - however if it is a streaming response, it is also mandated that it shall complete within the deadline, and if it does not
   *       the connection will be terminated regardless of status of the streaming response (this is because such response could be infinite,
   *       which could trap the server in a situation where it could not terminate if it were to wait for a response to "finish")
   *     - existing streaming responses must complete before the deadline as well.
   *       When the deadline is reached the connection will be terminated regardless of status of the streaming responses.
   *   - if user code does not reply with a response within the deadline we produce a special [[akka.http.javadsl.settings.ServerSettings.getTerminationDeadlineExceededResponse]]
   *     HTTP response (e.g. 503 Service Unavailable)
   *
   * 4) Keep draining incoming requests on existing connection:
   * - The existing connection will remain alive for until the `hardDeadline` is exceeded,
   *   yet no new requests will be delivered to the user handler. All such drained responses will be replied to with an
   *   termination response (as explained in phase 3).
   *
   * 5) Close still existing connections
   * - Connections are terminated forcefully once the `hardDeadline` is exceeded.
   *   The `whenTerminated` future is completed as well, so the graceful termination (of the `ActorSystem` or entire JVM
   *   itself can be safely performed, as by then it is known that no connections remain alive to this server).
   *
   * Note that the termination response is configurable in [[akka.http.javadsl.settings.ServerSettings]], and by default is an `503 Service Unavailable`,
   * with an empty response entity.
   *
   * @param hardDeadline timeout after which all requests and connections shall be forcefully terminated
   * @return completion stage which completes successfully with a marker object once all connections have been terminated
   */

  def terminate(hardDeadline: java.time.Duration): CompletionStage[HttpTerminated] = {
    delegate.terminate(FiniteDuration.apply(hardDeadline.toMillis, TimeUnit.MILLISECONDS))
      .map(_.asInstanceOf[HttpTerminated])(ExecutionContexts.sameThreadExecutionContext)
      .toJava
  }

  /**
   * Completes when the [[terminate]] is called and server termination is in progress.
   * Can be useful to make parts of your application aware that termination has been issued,
   * and they have [[java.time.Duration]] time remaining to clean-up before the server will forcefully close
   * existing connections.
   *
   * Note that while termination is in progress, no new connections will be accepted (i.e. termination implies prior [[unbind]]).
   */
  def whenTerminationSignalIssued: CompletionStage[java.time.Duration] =
    delegate.whenTerminationSignalIssued
      .map(deadline => deadline.time.asJava)(ExecutionContexts.sameThreadExecutionContext)
      .toJava

  /**
   * This completion stage completes when the termination process, as initiated by an [[terminate]] call has completed.
   * This means that the server is by then: unbound, and has closed all existing connections.
   *
   * This signal can for example be used to safely terminate the underlying ActorSystem.
   *
   * Note: This mechanism is currently NOT hooked into the Coordinated Shutdown mechanisms of Akka.
   *       TODO: This feature request is tracked by: https://github.com/akka/akka-http/issues/1210
   *
   * Note that this signal may be used for Coordinated Shutdown to proceed to next steps in the shutdown.
   * You may also explicitly depend on this completion stage to perform your next shutting down steps.
   */
  def whenTerminated: CompletionStage[HttpTerminated] =
    delegate.whenTerminated
      .map(_.asInstanceOf[HttpTerminated])(ExecutionContexts.sameThreadExecutionContext)
      .toJava
}

/** Type used to carry meaningful information when server termination has completed successfully. */
@DoNotInherit
abstract class HttpTerminated
