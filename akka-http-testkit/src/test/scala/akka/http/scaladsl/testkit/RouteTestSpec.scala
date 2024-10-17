/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.testkit

import akka.actor.ActorSystem
import akka.http.scaladsl.client.RequestBuilding.Get
import akka.testkit.TestKit
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class RouteTestSpec extends AnyWordSpec with Matchers with ScalaFutures {

  "RouteTest" should {

    "allow for pass through of exceptions in tests" in {
      implicit val system: ActorSystem = ActorSystem()
      try {
        import akka.http.scaladsl.server.directives.MethodDirectives._
        val route = get {
          throw new RuntimeException("BOOM")
        }

        val routeF = RouteTest.toFunctionPassThroughExceptions(route)
        intercept[RuntimeException] {
          routeF(Get("/whatever"))
        }.getMessage shouldBe "BOOM"

      } finally {
        TestKit.shutdownActorSystem(system)
      }
    }
  }

}
