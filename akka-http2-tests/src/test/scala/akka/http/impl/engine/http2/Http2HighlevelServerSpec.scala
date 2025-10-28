/*
 * Copyright (C) 2020-2025 Lightbend Inc. <https://akka.io>
 */

package akka.http.impl.engine.http2

import akka.http.impl.engine.ws.ByteStringSinkProbe
import akka.http.impl.util.AkkaSpecWithMaterializer
import akka.http.impl.util.ExampleHttpContexts
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.AttributeKey
import akka.http.scaladsl.model.ContentTypes
import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.model.HttpHeader
import akka.http.scaladsl.model.HttpMethod
import akka.http.scaladsl.model.HttpMethods
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.RequestResponseAssociation
import akka.http.scaladsl.model.StatusCode
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.settings.ClientConnectionSettings
import akka.http.scaladsl.settings.ServerSettings
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.stream.testkit.TestPublisher
import akka.stream.testkit.TestSubscriber
import akka.stream.testkit.Utils.TE
import akka.testkit.TestProbe
import akka.util.ByteString
import org.scalatest.concurrent.ScalaFutures

import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.Promise

class Http2HighlevelServerSpec extends AkkaSpecWithMaterializer(
  """akka.http.server.remote-address-header = on
     akka.http.server.http2.log-frames = on
     akka.http.server.log-unencrypted-network-bytes = 100
     akka.http.server.enable-http2 = on
     akka.http.client.http2.log-frames = on
     akka.http.client.log-unencrypted-network-bytes = 100
     akka.actor.serialize-messages = false
  """) with ScalaFutures {

  case class RequestId(id: String) extends RequestResponseAssociation
  val requestIdAttr = AttributeKey[RequestId]("requestId")

  "A HTTP 2 server" should {

    "return internal error when handler throws" in new TestSetup {
      sendClientRequest()
      val serverRequest = expectServerRequest()
      serverRequest.promise.failure(TE("boom"))
      val response = expectClientResponse()
      response.status should be(StatusCodes.InternalServerError)

      // stream not torn down so we can send another one
      sendClientRequest()
      val serverRequest2 = expectServerRequest()
      serverRequest2.promise.success(HttpResponse())
      val response2 = expectClientResponse()
      response2.status should be(StatusCodes.OK)

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
