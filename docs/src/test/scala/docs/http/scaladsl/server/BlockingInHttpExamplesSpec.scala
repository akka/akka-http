/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.scaladsl.server

import akka.actor.ActorSystem
import akka.http.scaladsl.server.{ Directives, Route }
import docs.CompileOnlySpec

import scala.concurrent.Future
import org.scalatest.wordspec.AnyWordSpec

class BlockingInHttpExamplesSpec extends AnyWordSpec with CompileOnlySpec
  with Directives {

  compileOnlySpec {
    val system: ActorSystem = ActorSystem()

    //#blocking-example-in-default-dispatcher
    // BAD (due to blocking in Future, on default dispatcher)
    implicit val defaultDispatcher = system.dispatcher

    val routes: Route = post {
      complete {
        Future { // uses defaultDispatcher
          Thread.sleep(5000) // will block on default dispatcher,
          System.currentTimeMillis().toString // Starving the routing infrastructure
        }
      }
    }
    //#blocking-example-in-default-dispatcher
  }

  compileOnlySpec {
    val system: ActorSystem = ActorSystem()

    //#blocking-example-in-dedicated-dispatcher
    // GOOD (the blocking is now isolated onto a dedicated dispatcher):
    implicit val blockingDispatcher = system.dispatchers.lookup("my-blocking-dispatcher")

    val routes: Route = post {
      complete {
        Future { // uses the good "blocking dispatcher" that we configured,
          // instead of the default dispatcher to isolate the blocking.
          Thread.sleep(5000)
          System.currentTimeMillis().toString
        }
      }
    }
    //#blocking-example-in-dedicated-dispatcher
  }

}
