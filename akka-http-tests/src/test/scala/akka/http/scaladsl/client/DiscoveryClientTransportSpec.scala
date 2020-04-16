/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.client

import java.net.InetAddress

import akka.discovery.{ Lookup, ServiceDiscovery }
import akka.discovery.ServiceDiscovery.{ Resolved, ResolvedTarget }
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.settings.{ClientConnectionSettings, ConnectionPoolSettings}
import akka.testkit.AkkaSpec

import scala.concurrent.Future
import scala.concurrent.duration._

class DiscoveryClientTransportSpec extends AkkaSpec {
  "The Akka Discovery-based ClientTransport" should {
    "allow discovering the actual hostname and port through Service Discovery" in {
      val discovery = new ServiceDiscovery {
        override def lookup(lookup: Lookup, resolveTimeout: FiniteDuration): Future[Resolved] = {
          require(lookup.serviceName == "foo")
          Future.successful(
            Resolved("foo", List(ResolvedTarget("127.0.0.1", Some(42), Some(InetAddress.getByAddress("foo", Array[Byte](127, 0, 0, 1))))))
          )
        }
      }

      val settings = ConnectionPoolSettings(system)
        .withConnectionSettings(ClientConnectionSettings(system).withTransport(new DiscoveryClientTransport(discovery)))
      val failure = Http().singleRequest(HttpRequest(uri = "http://foo"), settings = settings).failed.futureValue
      failure.getMessage should include("127.0.0.1:42")
    }

    "support connecting to a TLS server which does hostname verification" in pending
  }
}
