/*
 * Copyright (C) 2009-2026 Lightbend Inc. <https://akka.io>
 */

package akka.http.scaladsl.server

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{ HttpRequest, StatusCodes }
import akka.http.scaladsl.server.Directives.{ complete, onSuccess }
import akka.stream.SystemMaterializer
import akka.testkit.TestKit

import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContextExecutor, Promise }
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class RouteToFunctionExecutionContextSpec extends AnyWordSpec with Matchers {
  "Route.toFunction" should {
    "use the default system dispatcher when no ExecutionContext/Materializer are given" in {
      implicit val system: ActorSystem = ActorSystem("RouteToFunctionExecutionContextSpec1")
      try {
        val route: Route = complete(StatusCodes.OK)
        val handler = Route.toFunction(route)
        val response = Await.result(handler(HttpRequest()), 5.seconds)
        response.status shouldBe StatusCodes.OK
      } finally TestKit.shutdownActorSystem(system, verifySystemShutdown = true)
    }

    "use the given ExecutionContext instead of the system dispatcher when one is provided" in {
      implicit val system: ActorSystem = ActorSystem("RouteToFunctionExecutionContextSpec2")
      try {
        // Route completes asynchronously (via a not-yet-resolved Promise) so that the final
        // RouteResult -> HttpResponse mapping is actually scheduled on an ExecutionContext,
        // rather than run inline as FastFuture does for already-completed Futures.
        val promise = Promise[String]()
        val route: Route = onSuccess(promise.future) { s => complete(s) }

        val submissionCount = new AtomicInteger(0)
        val countingEc: ExecutionContextExecutor = new ExecutionContextExecutor {
          private val delegate = system.dispatcher
          override def execute(runnable: Runnable): Unit = {
            submissionCount.incrementAndGet()
            delegate.execute(runnable)
          }
          override def reportFailure(cause: Throwable): Unit = delegate.reportFailure(cause)
        }

        val handler = Route.toFunction(route, countingEc, SystemMaterializer(system).materializer)
        val responseFuture = handler(HttpRequest())
        promise.success("ok")
        val response = Await.result(responseFuture, 5.seconds)

        response.status shouldBe StatusCodes.OK
        submissionCount.get() should be > 0
      } finally TestKit.shutdownActorSystem(system, verifySystemShutdown = true)
    }

    "fall back to the system dispatcher/SystemMaterializer when null is passed explicitly" in {
      implicit val system: ActorSystem = ActorSystem("RouteToFunctionExecutionContextSpec3")
      try {
        val route: Route = complete(StatusCodes.OK)
        val handler = Route.toFunction(route, null, null)
        val response = Await.result(handler(HttpRequest()), 5.seconds)
        response.status shouldBe StatusCodes.OK
      } finally TestKit.shutdownActorSystem(system, verifySystemShutdown = true)
    }
  }
}
