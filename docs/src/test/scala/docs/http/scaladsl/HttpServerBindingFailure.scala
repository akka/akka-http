/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.scaladsl

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.server.Directives._

import scala.concurrent.Future

object HttpServerBindingFailure {
  def main(args: Array[String]): Unit = {
    implicit val system = ActorSystem()
    // needed for the future foreach in the end
    implicit val executionContext = system.dispatcher

    val handler = get {
      complete("Hello world!")
    }

    // let's say the OS won't allow us to bind to 80.
    val (host, port) = ("localhost", 80)
    val bindingFuture: Future[ServerBinding] =
      Http().bindAndHandle(handler, host, port)

    bindingFuture.failed.foreach { ex =>
      system.log.error(ex, "Failed to bind to {}:{}!", host, port)
    }
  }
}
