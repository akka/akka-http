/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.server

import java.util.concurrent.atomic.AtomicReference

import akka.Done
import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.settings.ServerSettings
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration.Duration
import scala.concurrent.{ Await, ExecutionContext, ExecutionContextExecutor, Future, Promise, blocking }
import scala.io.StdIn
import scala.util.{ Failure, Success, Try }

/**
 * DEPRECATED, consider https://doc.akka.io/docs/akka-http/current/quickstart-scala/ instead
 *
 * Bootstrap trait for Http Server. It helps booting up an akka-http server by only defining the desired routes.
 * It offers additional hooks to modify the default behavior.
 */
@deprecated("HttpApp this doesn't reflect the latest Akka APIs", "10.2.0")
abstract class HttpApp extends Directives {

  private val serverBinding = new AtomicReference[ServerBinding]()
  /**
   * [[ActorSystem]] used to start this server. Stopping this system will interfere with the proper functioning condition of the server.
   */
  protected val systemReference = new AtomicReference[ActorSystem]()

  /**
   * Start a server on the specified host and port.
   * Note that this method is blocking
   */
  def startServer(host: String, port: Int): Unit = {
    startServer(host, port, ServerSettings(ConfigFactory.load))
  }

  /**
   * Start a server on the specified host and port, using the provided [[ActorSystem]].
   * Note that this method is blocking
   *
   * @param system ActorSystem to use for starting the app,
   *   if `null` is passed in a new default ActorSystem will be created instead, which will
   *   be terminated when the server is stopped.
   */
  def startServer(host: String, port: Int, system: ActorSystem): Unit = {
    startServer(host, port, ServerSettings(system), Option(system))
  }

  /**
   * Start a server on the specified host and port, using the provided settings.
   * Note that this method is blocking.
   */
  def startServer(host: String, port: Int, settings: ServerSettings): Unit = {
    startServer(host, port, settings, None)
  }

  /**
   * Start a server on the specified host and port, using the provided settings and [[ActorSystem]].
   * Note that this method is blocking.
   *
   * @param system ActorSystem to use for starting the app,
   *   if `null` is passed in a new default ActorSystem will be created instead, which will
   *   be terminated when the server is stopped.
   */
  def startServer(host: String, port: Int, settings: ServerSettings, system: ActorSystem): Unit = {
    startServer(host, port, settings, Option(system))
  }

  /**
   * Start a server on the specified host and port, using the provided settings and [[ActorSystem]] if present.
   * Note that this method is blocking.
   *
   * @param system ActorSystem to use for starting the app,
   *   if `None` is passed in a new default ActorSystem will be created instead, which will
   *   be terminated when the server is stopped.
   */
  def startServer(host: String, port: Int, settings: ServerSettings, system: Option[ActorSystem]): Unit = {
    implicit val theSystem = system.getOrElse(ActorSystem(Logging.simpleName(this).replaceAll("\\$", "")))
    systemReference.set(theSystem)
    implicit val executionContext: ExecutionContextExecutor = theSystem.dispatcher

    val bindingFuture =
      Http().newServerAt(host, port)
        .withSettings(settings)
        .bind(routes)

    bindingFuture.onComplete {
      case Success(binding) =>
        //setting the server binding for possible future uses in the client
        serverBinding.set(binding)
        postHttpBinding(binding)
      case Failure(cause) =>
        postHttpBindingFailure(cause)
    }

    Await.ready(
      bindingFuture.flatMap(_ => waitForShutdownSignal(theSystem)), // chaining both futures to fail fast
      Duration.Inf) // It's waiting forever because maybe there is never a shutdown signal

    bindingFuture
      .flatMap(_.unbind())
      .onComplete(attempt => {
        postServerShutdown(attempt, theSystem)
        // we created the system. we should cleanup!
        if (system.isEmpty) theSystem.terminate()
      })
  }

  /**
   * It tries to retrieve the [[ServerBinding]] if the server has been successfully started. It fails otherwise.
   * You can use this method to attempt to retrieve the [[ServerBinding]] at any point in time to, for example, stop the server due to unexpected circumstances.
   */
  def binding(): Try[ServerBinding] = {
    if (serverBinding.get() == null) Failure(new IllegalStateException("Binding not yet stored. Have you called startServer?"))
    else Success(serverBinding.get())
  }

  /**
   * Hook that will be called just after the server termination. Override this method if you want to perform some cleanup actions after the server is stopped.
   * The `attempt` parameter is represented with a [[Try]] type that is successful only if the server was successfully shut down.
   */
  protected def postServerShutdown(attempt: Try[Done], system: ActorSystem): Unit = {
    systemReference.get().log.info("Shutting down the server")
  }

  /**
   * Hook that will be called just after the Http server binding is done. Override this method if you want to perform some actions after the server is up.
   */
  protected def postHttpBinding(binding: Http.ServerBinding): Unit = {
    systemReference.get().log.info(s"Server online at http://${binding.localAddress.getHostName}:${binding.localAddress.getPort}/")
  }

  /**
   * Hook that will be called in case the Http server binding fails. Override this method if you want to perform some actions after the server binding failed.
   */
  protected def postHttpBindingFailure(cause: Throwable): Unit = {
    systemReference.get().log.error(cause, s"Error starting the server ${cause.getMessage}")
  }

  /**
   * Hook that lets the user specify the future that will signal the shutdown of the server whenever completed.
   */
  protected def waitForShutdownSignal(system: ActorSystem)(implicit ec: ExecutionContext): Future[Done] = {
    val promise = Promise[Done]()
    sys.addShutdownHook {
      promise.trySuccess(Done)
    }
    Future {
      blocking {
        if (StdIn.readLine("Press RETURN to stop...\n") != null)
          promise.trySuccess(Done)
      }
    }
    promise.future
  }

  /**
   * Override to implement the routes that will be served by this http server.
   */
  protected def routes: Route
}
