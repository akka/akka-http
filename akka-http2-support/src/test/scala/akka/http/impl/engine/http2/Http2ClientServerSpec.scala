/*
 * Copyright (C) 2020-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.http.impl.engine.HttpIdleTimeoutException
import akka.http.impl.engine.ws.ByteStringSinkProbe
import akka.http.impl.util.{ AkkaSpecWithMaterializer, ExampleHttpContexts }
import akka.http.scaladsl.model.{ AttributeKey, ContentTypes, HttpEntity, HttpHeader, HttpMethod, HttpMethods, HttpRequest, HttpResponse, RequestResponseAssociation, StatusCode, StatusCodes, Uri, headers }
import akka.http.scaladsl.model.headers.HttpEncodings
import akka.http.scaladsl.settings.ClientConnectionSettings
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.http.scaladsl.Http
import akka.http.scaladsl.settings.ServerSettings
import akka.stream.StreamTcpException
import akka.stream.scaladsl.{ Sink, Source }
import akka.stream.testkit.{ TestPublisher, TestSubscriber }
import akka.testkit.TestProbe
import akka.util.ByteString
import org.scalatest.concurrent.ScalaFutures

import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.{ Future, Promise }

class Http2ClientServerSpec extends AkkaSpecWithMaterializer(
  """akka.http.server.remote-address-header = on
     akka.http.server.http2.log-frames = on
     akka.http.server.log-unencrypted-network-bytes = 100
     akka.http.server.preview.enable-http2 = on
     akka.http.client.http2.log-frames = on
     akka.http.client.log-unencrypted-network-bytes = 100
     akka.actor.serialize-messages = false
  """) with ScalaFutures {
  override protected def failOnSevereMessages: Boolean = true

  case class RequestId(id: String) extends RequestResponseAssociation
  val requestIdAttr = AttributeKey[RequestId]("requestId")

  "HTTP 2 implementation" should {
    "support simple round-trips" in new TestSetup {
      sendClientRequest(
        HttpRequest(
          method = HttpMethods.POST,
          entity = "ping",
          headers = headers.`Accept-Encoding`(HttpEncodings.gzip) :: Nil
        )
          .addAttribute(requestIdAttr, RequestId("request-1"))
      )

      val serverRequest = expectServerRequest()
      serverRequest.request.attribute(Http2.streamId) shouldBe Symbol("nonEmpty")
      serverRequest.request.method shouldBe HttpMethods.POST
      serverRequest.request.header[headers.`Accept-Encoding`] should not be empty
      Unmarshal(serverRequest.request.entity).to[String].futureValue shouldBe "ping"
      serverRequest.sendResponse(HttpResponse(entity = "pong"))

      val response = expectClientResponse()
      Unmarshal(response.entity).to[String].futureValue shouldBe "pong"
      response.attribute(requestIdAttr).get.id shouldBe "request-1"
    }
    "support multiple interleaved concurrent streams" in new TestSetup {
      val reqPub1 = sendClientRequestWithEntityStream("request-1")

      val serverRequest1 = expectServerRequest()
      serverRequest1.request.attribute(Http2.streamId) shouldBe Symbol("nonEmpty")
      serverRequest1.request.method shouldBe HttpMethods.POST

      val reqSub1 = serverRequest1.expectRequestEntityStream()
      reqPub1.sendNext(ByteString("ping"))
      reqSub1.expectUtf8EncodedString("ping")

      val reqPub2 = sendClientRequestWithEntityStream("request-2")
      val serverRequest2 = expectServerRequest()
      serverRequest2.request.attribute(Http2.streamId) shouldBe Symbol("nonEmpty")
      serverRequest2.request.method shouldBe HttpMethods.POST
      val reqSub2 = serverRequest2.expectRequestEntityStream()
      reqPub2.sendNext(ByteString("blub"))
      reqSub2.expectUtf8EncodedString("blub")

      // send and receive response to second request first

      val resPub2 = serverRequest2.sendResponseWithEntityStream()

      val (response2, resSub2) = expectClientResponseWithStream()
      response2.attribute(requestIdAttr).get.id shouldBe "request-2"
      resPub2.sendNext(ByteString("blip blup"))
      resSub2.expectUtf8EncodedString("blip blup")

      serverRequest1.sendResponse(HttpResponse(entity = "pong"))
      val response1 = expectClientResponse()
      response1.attribute(requestIdAttr).get.id shouldBe "request-1"
      Unmarshal(response1.entity).to[String].futureValue shouldBe "pong"
    }
    "support server-side idle-timeout" in new TestSetup {
      override def serverSettings: ServerSettings = super.serverSettings.mapTimeouts(_.withIdleTimeout(100.millis))

      clientResponsesIn.ensureSubscription()
      Thread.sleep(500)
      clientRequestsOut.expectCancellation()
      // expect idle timeout connection abort exception to propagate to user
      clientResponsesIn.expectError() shouldBe a[StreamTcpException]
    }
    "support client-side idle-timeout" in new TestSetup {
      override def clientSettings: ClientConnectionSettings = super.clientSettings.withIdleTimeout(100.millis)

      clientResponsesIn.ensureSubscription()
      Thread.sleep(500)
      clientRequestsOut.expectCancellation()
      // expect idle timeout exception to propagate to user
      clientResponsesIn.expectError() shouldBe a[HttpIdleTimeoutException]
    }
  }

  case class ServerRequest(request: HttpRequest, promise: Promise[HttpResponse]) {
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
    lazy val clientFlow =
      Http().connectionTo("akka.example.org")
        .withCustomHttpsConnectionContext(ExampleHttpContexts.exampleClientContext)
        .withClientConnectionSettings(clientSettings.withTransport(ExampleHttpContexts.proxyTransport(binding.localAddress)))
        .http2()
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
