/*
 * Copyright (C) 2020-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.scaladsl.server

//#testkit-actor-integration
import scala.concurrent.duration._
import scala.util.{ Failure, Success }

import akka.{ actor => untyped }
import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.typed.{ ActorRef, ActorSystem, Scheduler }
import akka.actor.typed.scaladsl.AskPattern._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.util.Timeout

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

object RouteUnderTest {
  case class Ping(replyTo: ActorRef[String])

  // Your route under test, scheduler is only needed as ask is used
  def route(someActor: ActorRef[Ping])(implicit scheduler: Scheduler, timeout: Timeout) = get {
    path("ping") {
      complete(someActor ? Ping.apply)
    }
  }
}

class TestKitWithActorSpec extends AnyWordSpec with Matchers with ScalatestRouteTest {
  import RouteUnderTest._

  // This test does not use the classic APIs,
  // so it needs to adapt the system:
  import akka.actor.typed.scaladsl.adapter._
  implicit val typedSystem: ActorSystem[_] = system.toTyped
  implicit val timeout: Timeout = Timeout(500.milliseconds)
  implicit val scheduler: untyped.Scheduler = system.scheduler

  "The service" should {
    "return a 'PONG!' response for GET requests to /ping" in {
      val probe = TestProbe[Ping]()
      val test = Get("/ping") ~> RouteUnderTest.route(probe.ref)
      val ping = probe.expectMessageType[Ping]
      ping.replyTo ! "PONG!"
      test ~> check {
        responseAs[String] shouldEqual "PONG!"
      }
    }
  }
}
//#testkit-actor-integration
