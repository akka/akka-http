/*
 * Copyright (C) 2018-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.client

import akka.http.impl.util.{ AkkaSpecWithMaterializer, ExampleHttpContexts }
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse, headers }
import akka.http.scaladsl.settings.{ ClientConnectionSettings, ConnectionPoolSettings }
import akka.stream.scaladsl.{ Flow, Sink, Source }
import akka.stream.testkit.{ TestPublisher, TestSubscriber, Utils }

import scala.concurrent.Await
import scala.concurrent.duration._

class ClientCancellationSpec extends AkkaSpecWithMaterializer {
  "Http client connections" must {
    "support cancellation in simple outgoing connection" in Utils.assertAllStagesStopped(new TestSetup {
      testCase(
        Http().outgoingConnection(address.getHostName, address.getPort))
    })

    "support cancellation in pooled outgoing connection" in Utils.assertAllStagesStopped(new TestSetup {
      testCase(
        Flow[HttpRequest]
          .map((_, ()))
          .via(Http().cachedHostConnectionPool(address.getHostName, address.getPort))
          .map(_._1.get)
      )
    })

    "support cancellation in simple outgoing connection with TLS" in Utils.assertAllStagesStopped(new TestSetup {
      pending
      testCase(
        Http().outgoingConnectionHttps("akka.example.org", 443, settings = settingsWithProxyTransport, connectionContext = ExampleHttpContexts.exampleClientContext)
      )
    })

    "support cancellation in pooled outgoing connection with TLS" in Utils.assertAllStagesStopped(new TestSetup {
      testCase(
        Flow[HttpRequest]
          .map((_, ()))
          .via(Http().cachedHostConnectionPoolHttps("akka.example.org", 443,
            settings = ConnectionPoolSettings(system).withConnectionSettings(settingsWithProxyTransport),
            connectionContext = ExampleHttpContexts.exampleClientContext))
          .map(_._1.get))
    })

  }

  class TestSetup {
    lazy val binding = Await.result(
      Http().bindAndHandleSync(
        { _ => HttpResponse(headers = headers.Connection("close") :: Nil) },
        "localhost", 0),
      5.seconds
    )
    lazy val address = binding.localAddress

    lazy val bindingTls = Await.result(
      Http().bindAndHandleSync(
        { _ => HttpResponse() }, // TLS client does full-close, no need for the connection:close header
        "localhost",
        0,
        connectionContext = ExampleHttpContexts.exampleServerContext),
      5.seconds
    )
    lazy val addressTls = bindingTls.localAddress

    def testCase(connection: Flow[HttpRequest, HttpResponse, Any]): Unit = {
      val requests = TestPublisher.probe[HttpRequest]()
      val responses = TestSubscriber.probe[HttpResponse]()
      Source.fromPublisher(requests).via(connection).runWith(Sink.fromSubscriber(responses))
      responses.request(1)
      requests.sendNext(HttpRequest())
      responses.expectNext().entity.dataBytes.runWith(Sink.cancelled)
      responses.cancel()
      requests.expectCancellation()

      binding.terminate(1.second)
      bindingTls.terminate(1.second)

      Http().shutdownAllConnectionPools()
    }

    def settingsWithProxyTransport: ClientConnectionSettings =
      ClientConnectionSettings(system)
        .withTransport(ExampleHttpContexts.proxyTransport(addressTls))
  }
}
