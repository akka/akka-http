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
import akka.stream.testkit.{ TestPublisher, TestSubscriber }
import akka.testkit.TestProbe
import akka.util.ByteString
import org.scalatest.concurrent.ScalaFutures

import scala.collection.immutable
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
    "establish a connection on first request and support simple round-trips" in new TestSetup {
      sendClientRequest(
        HttpRequest(
          method = HttpMethods.POST,
          entity = "ping",
          headers = headers.`Accept-Encoding`(HttpEncodings.gzip) :: Nil
        )
          .addAttribute(requestIdAttr, RequestId("request-1"))
      )
      clientResponsesIn.request(1)

      val serverRequest = expectServerRequest()
      serverRequest.request.attribute(Http2.streamId) shouldBe Symbol("nonEmpty")
      serverRequest.request.method shouldBe HttpMethods.POST
      serverRequest.request.header[headers.`Accept-Encoding`] should not be empty
      serverRequest.entityAsString shouldBe "ping"
      serverRequest.sendResponse(HttpResponse(entity = "pong"))

      val response = expectClientResponse()
      Unmarshal(response.entity).to[String].futureValue shouldBe "pong"
      response.attribute(requestIdAttr).get.id shouldBe "request-1"
    }

    "transparently reconnect when connection is closed" should {
      "when no requests are running" in new TestSetup {
        sendClientRequest(
          HttpRequest(
            method = HttpMethods.POST,
            uri = "/ping1",
            entity = "ping",
            headers = headers.`Accept-Encoding`(HttpEncodings.gzip) :: Nil
          )
            .addAttribute(requestIdAttr, RequestId("request-1"))
        )
        clientResponsesIn.request(1)

        val serverRequest = expectServerRequest()
        serverRequest.request.attribute(Http2.streamId) shouldBe Symbol("nonEmpty")
        serverRequest.request.method shouldBe HttpMethods.POST
        serverRequest.request.header[headers.`Accept-Encoding`] should not be empty
        serverRequest.entityAsString shouldBe "ping"
        serverRequest.sendResponse(HttpResponse(entity = "pong"))

        val response = expectClientResponse()
        Unmarshal(response.entity).to[String].futureValue shouldBe "pong"
        response.attribute(requestIdAttr).get.id shouldBe "request-1"

        killConnection()
        Thread.sleep(100)
        sendClientRequest(
          HttpRequest(
            method = HttpMethods.POST,
            uri = "/ping2",
            entity = "ping2",
            headers = headers.`Accept-Encoding`(HttpEncodings.gzip) :: Nil
          )
            .addAttribute(requestIdAttr, RequestId("request-2"))
        )
        clientResponsesIn.request(1)

        val serverRequest2 = expectServerRequest()
        serverRequest2.entityAsString shouldBe "ping2"
      }
      "when some requests are waiting for a response" in new TestSetup {
        sendClientRequest(
          HttpRequest(
            method = HttpMethods.POST,
            uri = "/ping1",
            entity = "ping",
            headers = headers.`Accept-Encoding`(HttpEncodings.gzip) :: Nil
          )
            .addAttribute(requestIdAttr, RequestId("request-1"))
        )
        clientResponsesIn.request(1)

        expectServerRequest()

        sendClientRequest(
          HttpRequest(
            method = HttpMethods.POST,
            uri = "/ping2",
            entity = "ping2",
            headers = headers.`Accept-Encoding`(HttpEncodings.gzip) :: Nil
          )
            .addAttribute(requestIdAttr, RequestId("request-2"))
        )
        clientResponsesIn.request(1)

        expectServerRequest()

        killConnection()
        val response = expectClientResponse()
        response.status shouldBe StatusCodes.BadGateway
        response.attribute(requestIdAttr).get.id shouldBe "request-1"
        Unmarshal(response.entity).to[String].futureValue shouldBe "The server closed the connection before delivering a response."

        val response2 = expectClientResponse()
        response2.status shouldBe StatusCodes.BadGateway
        response2.attribute(requestIdAttr).get.id shouldBe "request-2"
        Unmarshal(response2.entity).to[String].futureValue shouldBe "The server closed the connection before delivering a response."

        Thread.sleep(100)

        sendClientRequest(
          HttpRequest(
            method = HttpMethods.POST,
            entity = "ping2",
            headers = headers.`Accept-Encoding`(HttpEncodings.gzip) :: Nil
          )
            .addAttribute(requestIdAttr, RequestId("request-2"))
        )
        clientResponsesIn.request(1)

        val serverRequest2 = expectServerRequest()
        serverRequest2.entityAsString shouldBe "ping2"
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

    private lazy val serverRequestProbe = TestProbe()
    private lazy val handler: HttpRequest => Future[HttpResponse] = { req =>
      val p = Promise[HttpResponse]()
      serverRequestProbe.ref ! ServerRequest(req, p)
      p.future
    }
    lazy val binding =
      Http().newServerAt("localhost", 0)
        .enableHttps(ExampleHttpContexts.exampleServerContext)
        .withSettings(serverSettings)
        .bind(handler).futureValue

    val transport = new ClientTransport {
      override def connectTo(host: String, port: Int, settings: ClientConnectionSettings)(implicit system: ActorSystem): Flow[ByteString, ByteString, Future[Http.OutgoingConnection]] =
        Flow.fromGraph(KillSwitches.single[ByteString])
          .mapMaterializedValue { killer =>
            killProbe.ref ! killer
          }
          .viaMat(ClientTransport.TCP.connectTo(binding.localAddress.getHostString, binding.localAddress.getPort, settings)(system))(Keep.right)
    }

    lazy val clientFlow =
      Http().connectionTo("akka.example.org")
        .withCustomHttpsConnectionContext(ExampleHttpContexts.exampleClientContext)
        .withClientConnectionSettings(clientSettings.withTransport(transport))
        .managedPersistentHttp2()

    lazy val clientRequestsOut = TestPublisher.probe[HttpRequest]()
    lazy val clientResponsesIn = TestSubscriber.probe[HttpResponse]()
    Source.fromPublisher(clientRequestsOut)
      .via(clientFlow)
      .runWith(Sink.fromSubscriber(clientResponsesIn))

    // client-side
    def sendClientRequestWithEntityStream(
      requestId: String,
      method:    HttpMethod                = HttpMethods.POST,
      uri:       Uri                       = Uri./,
      headers:   immutable.Seq[HttpHeader] = Nil): TestPublisher.Probe[ByteString] = {
      val probe = TestPublisher.probe[ByteString]()
      sendClientRequest(
        HttpRequest(method, uri, headers, HttpEntity(ContentTypes.`application/octet-stream`, Source.fromPublisher(probe)))
          .addAttribute(requestIdAttr, RequestId(requestId))
      )
      probe
    }
    def sendClientRequest(request: HttpRequest = HttpRequest()): Unit = clientRequestsOut.sendNext(request)
    def expectClientResponse(): HttpResponse = clientResponsesIn.requestNext()
    def expectClientResponseWithStream(): (HttpResponse, ByteStringSinkProbe) = {
      val res = expectClientResponse()
      val probe = ByteStringSinkProbe()
      res.entity.dataBytes.runWith(probe.sink)
      res -> probe
    }

    // server-side
    def expectServerRequest(): ServerRequest = serverRequestProbe.expectMsgType[ServerRequest]
  }
}
