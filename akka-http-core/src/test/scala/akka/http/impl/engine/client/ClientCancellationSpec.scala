/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.client

import akka.http.impl.util.AkkaSpecWithMaterializer
import javax.net.ssl.SSLContext
import akka.http.scaladsl.{ ConnectionContext, Http }
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse }
import akka.stream.SystemMaterializer
import akka.stream.scaladsl.{ Flow, Sink, Source }
import akka.stream.testkit.{ TestPublisher, TestSubscriber, Utils }
import akka.http.scaladsl.model.headers
import akka.testkit.SocketUtil

class ClientCancellationSpec extends AkkaSpecWithMaterializer {
  // TODO document why this explicit materializer is needed here?
  val noncheckedMaterializer = SystemMaterializer(system).materializer

  "Http client connections" must {
    val address = SocketUtil.temporaryServerAddress()
    Http().bindAndHandleSync(
      { req => HttpResponse(headers = headers.Connection("close") :: Nil) },
      address.getHostName,
      address.getPort)(noncheckedMaterializer)

    val addressTls = SocketUtil.temporaryServerAddress()
    Http().bindAndHandleSync(
      { req => HttpResponse() }, // TLS client does full-close, no need for the connection:close header
      addressTls.getHostName,
      addressTls.getPort,
      connectionContext = ConnectionContext.https(SSLContext.getDefault))(noncheckedMaterializer)

    def testCase(connection: Flow[HttpRequest, HttpResponse, Any]): Unit = Utils.assertAllStagesStopped {
      val requests = TestPublisher.probe[HttpRequest]()
      val responses = TestSubscriber.probe[HttpResponse]()
      Source.fromPublisher(requests).via(connection).runWith(Sink.fromSubscriber(responses))
      responses.request(1)
      requests.sendNext(HttpRequest())
      responses.expectNext().entity.dataBytes.runWith(Sink.cancelled)
      responses.cancel()
      requests.expectCancellation()
    }

    "support cancellation in simple outgoing connection" in {
      testCase(
        Http().outgoingConnection(address.getHostName, address.getPort))
    }

    "support cancellation in pooled outgoing connection" in {
      testCase(
        Flow[HttpRequest]
          .map((_, ()))
          .via(Http().cachedHostConnectionPool(address.getHostName, address.getPort))
          .map(_._1.get))
    }

    "support cancellation in simple outgoing connection with TLS" in {
      pending
      testCase(
        Http().outgoingConnectionHttps(addressTls.getHostName, addressTls.getPort))
    }

    "support cancellation in pooled outgoing connection with TLS" in {
      pending
      testCase(
        Flow[HttpRequest]
          .map((_, ()))
          .via(Http().cachedHostConnectionPoolHttps(addressTls.getHostName, addressTls.getPort))
          .map(_._1.get))
    }

  }

}
