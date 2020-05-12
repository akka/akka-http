/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.scaladsl.server

import akka.Done
import akka.actor.CoordinatedShutdown
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import docs.CompileOnlySpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent._
import scala.concurrent.duration._
import scala.io.StdIn

class ServerShutdownExampleSpec extends AnyWordSpec with Matchers
  with CompileOnlySpec {

  "mount coordinated shutdown" in compileOnlySpec {
    import akka.http.scaladsl.model.{ContentTypes, HttpEntity}
    import akka.http.scaladsl.server.Route

    implicit val system = ActorSystem(Behaviors.empty, "http-server")
    implicit val ec: ExecutionContext = system.executionContext

    val routes: Route =
      path("hello") {
        get {
          complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "<h1>Say hello to akka-http</h1>"))
        }
      }

    // #suggested
    val bindingFuture = Http()
      .bindAndHandle(handler = routes, interface = "localhost", port = 8080)
      .map(_.addToCoordinatedShutdown(hardTerminationDeadline = 10.seconds))
    // #suggested

    bindingFuture.failed.foreach { cause =>
      system.log.error(s"Error starting the server ${cause.getMessage}", cause)
    }

    Await.ready(
      bindingFuture.flatMap(_ => waitForShutdownSignal(system)), // chaining both futures to fail fast
      Duration.Inf) // It's waiting forever because maybe there is never a shutdown signal

    // #shutdown
    case object UserInitiatedShutdown extends CoordinatedShutdown.Reason

    CoordinatedShutdown(system).run(UserInitiatedShutdown)
    // #shutdown
  }

  protected def waitForShutdownSignal(system: ActorSystem[_])(implicit ec: ExecutionContext): Future[Done] = {
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


}
