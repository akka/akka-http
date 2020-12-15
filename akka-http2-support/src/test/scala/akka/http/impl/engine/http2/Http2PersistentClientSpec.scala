/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.actor.ActorSystem
import akka.http.impl.engine.ws.ByteStringSinkProbe
import akka.http.impl.util.{ AkkaSpecWithMaterializer, ExampleHttpContexts }
import akka.http.scaladsl.model.{ AttributeKey, ContentTypes, HttpEntity, HttpHeader, HttpMethod, HttpMethods, HttpRequest, HttpResponse, RequestResponseAssociation, StatusCode, StatusCodes, Uri, headers }
import akka.http.scaladsl.model.headers.HttpEncodings
import akka.http.scaladsl.settings.ClientConnectionSettings
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.http.scaladsl.{ ClientTransport, Http }
import akka.http.scaladsl.settings.ServerSettings
import akka.stream.{ KillSwitches, UniqueKillSwitch }
import akka.stream.scaladsl.{ Flow, Keep, Sink, Source }
import akka.stream.testkit.scaladsl.StreamTestKit
import akka.stream.testkit.{ TestPublisher, TestSubscriber }
import akka.testkit.TestProbe
import akka.util.ByteString
import org.scalatest.concurrent.ScalaFutures

import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.{ Future, Promise }

class Http2PersistentClientSpec extends AkkaSpecWithMaterializer(
  """akka.http.server.remote-address-header = on
     #akka.http.server.http2.log-frames = on
     #akka.http.server.log-unencrypted-network-bytes = 100
     akka.http.server.preview.enable-http2 = on
     akka.http.client.http2.log-frames = on
     akka.http.client.log-unencrypted-network-bytes = 100
     akka.actor.serialize-messages = false
  """) with ScalaFutures {
  override def failOnSevereMessages: Boolean = true

  case class RequestId(id: String) extends RequestResponseAssociation
  val requestIdAttr = AttributeKey[RequestId]("requestId")

  "HTTP 2 managed persistent connection" should {
    "establish a connection on first request and support simple round-trips" inAssertAllStagesStopped new TestSetup {
      client.sendRequest(
        HttpRequest(
          method = HttpMethods.POST,
          entity = "ping",
          headers = headers.`Accept-Encoding`(HttpEncodings.gzip) :: Nil
        )
          .addAttribute(requestIdAttr, RequestId("request-1"))
      )
      client.responsesIn.request(1)

      val serverRequest = server.expectRequest()
      serverRequest.request.attribute(Http2.streamId) shouldBe Symbol("nonEmpty")
      serverRequest.request.method shouldBe HttpMethods.POST
      serverRequest.request.header[headers.`Accept-Encoding`] should not be empty
      serverRequest.entityAsString shouldBe "ping"
      serverRequest.sendResponse(HttpResponse(entity = "pong"))

      val response = client.expectResponse()
      Unmarshal(response.entity).to[String].futureValue shouldBe "pong"
      response.attribute(requestIdAttr).get.id shouldBe "request-1"
    }

    "transparently reconnect when connection is closed" should {
      "when no requests are running" inAssertAllStagesStopped new TestSetup {
        client.sendRequest(
          HttpRequest(
            method = HttpMethods.POST,
            uri = "/ping1",
            entity = "ping",
            headers = headers.`Accept-Encoding`(HttpEncodings.gzip) :: Nil
          )
            .addAttribute(requestIdAttr, RequestId("request-1"))
        )
        client.responsesIn.request(1)

        val serverRequest = server.expectRequest()
        serverRequest.request.attribute(Http2.streamId) shouldBe Symbol("nonEmpty")
        serverRequest.request.method shouldBe HttpMethods.POST
        serverRequest.request.header[headers.`Accept-Encoding`] should not be empty
        serverRequest.entityAsString shouldBe "ping"
        serverRequest.sendResponse(HttpResponse(entity = "pong"))

        val response = client.expectResponse()
        Unmarshal(response.entity).to[String].futureValue shouldBe "pong"
        response.attribute(requestIdAttr).get.id shouldBe "request-1"

        killConnection()
        Thread.sleep(100)
        client.sendRequest(
          HttpRequest(
            method = HttpMethods.POST,
            uri = "/ping2",
            entity = "ping2",
            headers = headers.`Accept-Encoding`(HttpEncodings.gzip) :: Nil
          )
            .addAttribute(requestIdAttr, RequestId("request-2"))
        )
        client.responsesIn.request(1)

        val serverRequest2 = server.expectRequest()
        serverRequest2.entityAsString shouldBe "ping2"
      }
      "when some requests are waiting for a response" inAssertAllStagesStopped new TestSetup {
        client.sendRequest(
          HttpRequest(
            method = HttpMethods.POST,
            uri = "/ping1",
            entity = "ping",
            headers = headers.`Accept-Encoding`(HttpEncodings.gzip) :: Nil
          )
            .addAttribute(requestIdAttr, RequestId("request-1"))
        )
        client.responsesIn.request(1)

        server.expectRequest()

        client.sendRequest(
          HttpRequest(
            method = HttpMethods.POST,
            uri = "/ping2",
            entity = "ping2",
            headers = headers.`Accept-Encoding`(HttpEncodings.gzip) :: Nil
          )
            .addAttribute(requestIdAttr, RequestId("request-2"))
        )
        client.responsesIn.request(1)

        server.expectRequest()

        killConnection()
        val response = client.expectResponse()
        response.status shouldBe StatusCodes.BadGateway
        response.attribute(requestIdAttr).get.id shouldBe "request-1"
        Unmarshal(response.entity).to[String].futureValue shouldBe "The server closed the connection before delivering a response."

        val response2 = client.expectResponse()
        response2.status shouldBe StatusCodes.BadGateway
        response2.attribute(requestIdAttr).get.id shouldBe "request-2"
        Unmarshal(response2.entity).to[String].futureValue shouldBe "The server closed the connection before delivering a response."

        Thread.sleep(100)

        client.sendRequest(
          HttpRequest(
            method = HttpMethods.POST,
            entity = "ping2",
            headers = headers.`Accept-Encoding`(HttpEncodings.gzip) :: Nil
          )
            .addAttribute(requestIdAttr, RequestId("request-2"))
        )
        client.responsesIn.request(1)

        val serverRequest2 = server.expectRequest()
        serverRequest2.entityAsString shouldBe "ping2"
      }
    }
    "not leak any stages if completed" should {
      "when waiting for a response" inAssertAllStagesStopped new TestSetup {
        client.sendRequest(
          HttpRequest(
            method = HttpMethods.POST,
            uri = "/ping1",
            entity = "ping",
            headers = headers.`Accept-Encoding`(HttpEncodings.gzip) :: Nil
          )
            .addAttribute(requestIdAttr, RequestId("request-1"))
        )
        client.responsesIn.request(1)

        server.expectRequest()
      }
    }
  }

  case class ServerRequest(request: HttpRequest, promise: Promise[HttpResponse]) {
    lazy val entityAsString = Unmarshal(request.entity).to[String].futureValue

    def sendResponse(response: HttpResponse): Unit =
      promise.success(response.addAttribute(Http2.streamId, request.attribute(Http2.streamId).get))

    def sendResponseWithEntityStream(
      status:  StatusCode                = StatusCodes.OK,
      headers: immutable.Seq[HttpHeader] = Nil): TestPublisher.Probe[ByteString] = {
      val probe = TestPublisher.probe[ByteString]()
      sendResponse(HttpResponse(status, headers, HttpEntity(ContentTypes.`application/octet-stream`, Source.fromPublisher(probe))))
      probe
    }

    def expectRequestEntityStream(): ByteStringSinkProbe = {
      val probe = ByteStringSinkProbe()
      request.entity.dataBytes.runWith(probe.sink)
      probe
    }
  }
  class TestSetup {
    def serverSettings: ServerSettings = ServerSettings(system)
    def clientSettings: ClientConnectionSettings = ClientConnectionSettings(system)

    val killProbe = TestProbe()
    def killConnection(): Unit = killProbe.expectMsgType[UniqueKillSwitch].abort(new RuntimeException("connection was killed"))

    object server {
      private lazy val requestProbe = TestProbe()
      private lazy val handler: HttpRequest => Future[HttpResponse] = { req =>
        val p = Promise[HttpResponse]()
        requestProbe.ref ! ServerRequest(req, p)
        p.future
      }
      lazy val binding =
        Http().newServerAt("localhost", 0)
          .enableHttps(ExampleHttpContexts.exampleServerContext)
          .withSettings(serverSettings)
          .bind(handler).futureValue

      def expectRequest(): ServerRequest = requestProbe.expectMsgType[ServerRequest]
    }
    object client {
      val transport = new ClientTransport {
        override def connectTo(host: String, port: Int, settings: ClientConnectionSettings)(implicit system: ActorSystem): Flow[ByteString, ByteString, Future[Http.OutgoingConnection]] =
          Flow.fromGraph(KillSwitches.single[ByteString])
            .mapMaterializedValue { killer =>
              killProbe.ref ! killer
            }
            .viaMat(ClientTransport.TCP.connectTo(server.binding.localAddress.getHostString, server.binding.localAddress.getPort, settings)(system))(Keep.right)
      }

      lazy val clientFlow =
        Http().connectionTo("akka.example.org")
          .withCustomHttpsConnectionContext(ExampleHttpContexts.exampleClientContext)
          .withClientConnectionSettings(clientSettings.withTransport(transport))
          .managedPersistentHttp2()

      lazy val requestsOut = TestPublisher.probe[HttpRequest]()
      lazy val responsesIn = TestSubscriber.probe[HttpResponse]()
      Source.fromPublisher(requestsOut)
        .via(clientFlow)
        .runWith(Sink.fromSubscriber(responsesIn))

      def sendRequestWithEntityStream(
        requestId: String,
        method:    HttpMethod                = HttpMethods.POST,
        uri:       Uri                       = Uri./,
        headers:   immutable.Seq[HttpHeader] = Nil): TestPublisher.Probe[ByteString] = {
        val probe = TestPublisher.probe[ByteString]()
        sendRequest(
          HttpRequest(method, uri, headers, HttpEntity(ContentTypes.`application/octet-stream`, Source.fromPublisher(probe)))
            .addAttribute(requestIdAttr, RequestId(requestId))
        )
        probe
      }
      def sendRequest(request: HttpRequest = HttpRequest()): Unit = requestsOut.sendNext(request)
      def expectResponse(): HttpResponse = responsesIn.requestNext()
      def expectResponseWithStream(): (HttpResponse, ByteStringSinkProbe) = {
        val res = expectResponse()
        val probe = ByteStringSinkProbe()
        res.entity.dataBytes.runWith(probe.sink)
        res -> probe
      }
    }

    def shutdown(): Unit = {
      client.requestsOut.sendComplete()
      client.responsesIn.cancel()

      server.binding.terminate(100.millis).futureValue
    }
  }

  implicit class InWithStoppedStages(name: String) {
    def inAssertAllStagesStopped(runTest: => TestSetup) =
      name in StreamTestKit.assertAllStagesStopped {
        val setup = runTest
        setup.shutdown()
        // and then assert that all stages, substreams in particular, are stopped
      }
  }
}
