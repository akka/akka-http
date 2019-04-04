/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.client.pool

import akka.Done
import akka.actor.ActorRef
import akka.http.impl.engine.client.PoolGateway
import akka.http.impl.engine.client.PoolMasterActor.{ SendRequest, Shutdown, StartPool }
import akka.http.impl.settings.{ ConnectionPoolSetup, HostConnectionPoolSetup }
import akka.http.scaladsl.ConnectionContext
import akka.http.scaladsl.model.{ HttpMethods, HttpRequest, HttpResponse, StatusCodes }
import akka.http.scaladsl.settings.ConnectionPoolSettings
import akka.stream.ActorMaterializer
import akka.testkit.TestActor.AutoPilot
import akka.testkit.{ AkkaSpec, TestProbe }

import scala.concurrent.Await
import scala.concurrent.duration._

class PoolGatewaySpec extends AkkaSpec {
  implicit val materializer = ActorMaterializer()

  "PoolGateway#shutdown" should {
    "proxy request to PoolMasterActor" in {
      val gatewayActorProbe = TestProbe()

      val hcps = HostConnectionPoolSetup("localhost", 8080,
        ConnectionPoolSetup(ConnectionPoolSettings(system), ConnectionContext.noEncryption(), log))
      val poolGateway = new PoolGateway(gatewayActorProbe.ref, hcps, PoolGateway.UniqueGateway(1))

      val result = poolGateway.shutdown()

      gatewayActorProbe.expectMsgPF() {
        case Shutdown(poolGatewayRef, _) if poolGatewayRef == poolGateway ⇒ ()
      }

      Await.result(result, 3.seconds) shouldBe Done
    }

    "return same future on each call" in {
      val gatewayActorProbe = TestProbe()

      val hcps = HostConnectionPoolSetup("localhost", 8080,
        ConnectionPoolSetup(ConnectionPoolSettings(system), ConnectionContext.noEncryption(), log))
      val poolGateway = new PoolGateway(gatewayActorProbe.ref, hcps, PoolGateway.UniqueGateway(1))

      val firstFuture = poolGateway.shutdown()
      val secondFuture = poolGateway.shutdown()

      firstFuture shouldBe theSameInstanceAs(secondFuture)
    }
  }

  "PoolGateway#apply" should {
    "pass http request to gateway actor" in {
      val gatewayActorProbe = TestProbe()
      val httpRequest = HttpRequest(HttpMethods.GET)
      val httpResponse = HttpResponse(StatusCodes.OK)

      gatewayActorProbe.setAutoPilot(new AutoPilot {
        override def run(sender: ActorRef, msg: Any): AutoPilot = msg match {
          case SendRequest(_, _, requestPromise, _) ⇒
            requestPromise.trySuccess(httpResponse)
            this
        }
      })
      val hcps = HostConnectionPoolSetup("localhost", 8080,
        ConnectionPoolSetup(ConnectionPoolSettings(system), ConnectionContext.noEncryption(), log))
      val poolGateway = new PoolGateway(gatewayActorProbe.ref, hcps, PoolGateway.UniqueGateway(1))

      Await.result(poolGateway.apply(httpRequest), 3.seconds) shouldBe httpResponse

      gatewayActorProbe.expectMsgPF() {
        case SendRequest(gateway, request, _, _) if gateway == poolGateway && request == httpRequest ⇒ ()
      }
    }
  }

  "PoolGateway#startPool" should {
    "send startup command to gateway actor" in {
      val gatewayActorProbe = TestProbe()
      val hcps = HostConnectionPoolSetup("localhost", 8080,
        ConnectionPoolSetup(ConnectionPoolSettings(system), ConnectionContext.noEncryption(), log))
      val poolGateway = new PoolGateway(gatewayActorProbe.ref, hcps, PoolGateway.UniqueGateway(1))

      poolGateway.startPool()

      gatewayActorProbe.expectMsgType[StartPool]
    }
  }
}
