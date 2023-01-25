/*
 * Copyright (C) 2020-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.impl.engine.ws.ByteStringSinkProbe
import akka.http.impl.util.{ AkkaSpecWithMaterializer, ExampleHttpContexts }
import akka.http.scaladsl.model.{ AttributeKey, AttributeKeys, ContentTypes, HttpEntity, HttpHeader, HttpMethod, HttpMethods, HttpRequest, HttpResponse, RequestResponseAssociation, StatusCode, StatusCodes, Uri, headers }
import akka.http.scaladsl.model.headers.HttpEncodings
import akka.http.scaladsl.settings.ClientConnectionSettings
import akka.http.scaladsl.settings.Http2ClientSettings
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.http.scaladsl.{ ClientTransport, Http }
import akka.http.scaladsl.settings.ServerSettings
import akka.stream.{ KillSwitches, StreamTcpException, UniqueKillSwitch }
import akka.stream.scaladsl.{ Flow, Keep, Sink, Source }
import akka.stream.testkit.scaladsl.StreamTestKit
import akka.stream.testkit.{ TestPublisher, TestSubscriber }
import akka.testkit.TestProbe
import akka.util.ByteString
import org.scalatest.concurrent.ScalaFutures

import java.net.InetSocketAddress
import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.{ Future, Promise }

class Http2PersistentClientTlsSpec extends Http2PersistentClientSpec(true)
class Http2PersistentClientPlaintextSpec extends Http2PersistentClientSpec(false)

abstract class Http2PersistentClientSpec(tls: Boolean) extends AkkaSpecWithMaterializer(
  // FIXME: would rather use remote-address-attribute, but that doesn't work with HTTP/2
  // see https://github.com/akka/akka-http/issues/3707
  """akka.http.server.remote-address-attribute = on
     akka.http.server.enable-http2 = on
     akka.http.client.http2.log-frames = on
     akka.http.client.http2.max-persistent-attempts = 5
     akka.http.client.log-unencrypted-network-bytes = 100
     akka.actor.serialize-messages = false
     akka.http.server.http2.completion-timeout=100ms
     akka.http.client.http2.completion-timeout=100ms
  """) with ScalaFutures {
  override def failOnSevereMessages: Boolean = true
  private val notSevere = Set("ChannelReadable", "WriteAck")
  override protected def isSevere(event: Logging.LogEvent): Boolean =
    event.level <= Logging.WarningLevel &&
      // fix for https://github.com/akka/akka-http/issues/3732 / https://github.com/akka/akka/issues/29330
      !notSevere.exists(cand => event.message.toString.contains(cand))

  case class RequestId(id: String) extends RequestResponseAssociation
  val requestIdAttr = AttributeKey[RequestId]("requestId")

  s"HTTP 2 managed persistent connection (tls = $tls)" should {
    "establish a connection on first request and support simple round-trips" inAssertAllStagesStopped new TestSetup(tls) {
      client.sendRequest(
        HttpRequest(
          method = HttpMethods.POST,
          entity = "ping",
          headers = headers.`Accept-Encoding`(HttpEncodings.gzip) :: Nil
        )
          .addAttribute(requestIdAttr, RequestId("request-1"))
      )
      // need some demand on response side, otherwise, no requests will be pulled in
      client.responsesIn.request(1)

      val serverRequest = server.expectRequest()
      serverRequest.request.attribute(Http2.streamId) should not be empty
      serverRequest.request.method shouldBe HttpMethods.POST
      serverRequest.request.header[headers.`Accept-Encoding`] should not be empty
      serverRequest.entityAsString shouldBe "ping"

      // now respond
      server.sendResponseFor(serverRequest, HttpResponse(entity = "pong"))

      val response = client.expectResponse()
      Unmarshal(response.entity).to[String].futureValue shouldBe "pong"
      response.attribute(requestIdAttr).get.id shouldBe "request-1"
    }

    def reconnectionTests(withBackoff: Boolean): Unit = {
      val changeSettings: Http2ClientSettings => Http2ClientSettings =
        if (withBackoff) s => s.withBaseConnectionBackoff(300.millis).withMaxConnectionBackoff(800.millis)
        else s => s.withBaseConnectionBackoff(Duration.Zero)

      s"transparently reconnect when connection is closed (with backoff: ${withBackoff})" should {
        "when no requests are running" inAssertAllStagesStopped new TestSetup(tls) {
          override def clientSettings: ClientConnectionSettings = super.clientSettings.mapHttp2Settings(changeSettings)
          client.sendRequest(
            HttpRequest(
              method = HttpMethods.POST,
              uri = "/ping1",
              entity = "ping",
              headers = headers.`Accept-Encoding`(HttpEncodings.gzip) :: Nil
            )
              .addAttribute(requestIdAttr, RequestId("request-1"))
          )
          // need some demand on response side, otherwise, no requests will be pulled in
          client.responsesIn.request(1)

          val serverRequest = server.expectRequest()
          serverRequest.request.attribute(Http2.streamId) should not be empty
          serverRequest.request.method shouldBe HttpMethods.POST
          serverRequest.request.header[headers.`Accept-Encoding`] should not be empty
          serverRequest.entityAsString shouldBe "ping"
          val clientPort = serverRequest.clientPort

          // now respond
          server.sendResponseFor(serverRequest, HttpResponse(entity = "pong"))

          val response = client.expectResponse()
          Unmarshal(response.entity).to[String].futureValue shouldBe "pong"
          response.attribute(requestIdAttr).get.id shouldBe "request-1"

          // now kill connection from outside
          killConnection()

          // wait a bit to avoid race condition
          Thread.sleep(100)

          // requests should now be handled on new connection
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
          // request should have come in through another connection
          serverRequest2.clientPort should not be (clientPort)

          client.requestsOut.sendComplete()
          server.sendResponseFor(serverRequest2, HttpResponse(entity = "pong2"))
          val clientResponse2 = client.expectResponse()
          Unmarshal(clientResponse2.entity).to[String].futureValue shouldBe "pong2"
        }

        "when some requests are waiting for a response" inAssertAllStagesStopped new TestSetup(tls) {
          override def clientSettings: ClientConnectionSettings = super.clientSettings.mapHttp2Settings(changeSettings)
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
          val clientPort = serverRequest.clientPort

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

          // now kill connection from outside. This will make some demux stages to start a
          // timeout trigger and eventually complete
          killConnection()

          // check that ongoing requests are properly dealt with
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
          // request should have come in through another connection
          serverRequest2.clientPort should not be (clientPort)
        }
        "when the first connection fails to materialize" inAssertAllStagesStopped new TestSetup(tls) {
          var first = true
          override def clientSettings: ClientConnectionSettings = super.clientSettings.withTransport(ClientTransport.withCustomResolver((host, port) => {
            if (first) {
              first = false
              // First request returns an address where we are not listening
              Future.successful(new InetSocketAddress("example.invalid", 80))
            } else
              Future.successful(server.binding.localAddress)
          })).mapHttp2Settings(changeSettings)

          client.sendRequest(
            HttpRequest(
              method = HttpMethods.POST,
              entity = "ping",
              headers = headers.`Accept-Encoding`(HttpEncodings.gzip) :: Nil
            )
              .addAttribute(requestIdAttr, RequestId("request-1"))
          )
          // need some demand on response side, otherwise, no requests will be pulled in
          client.responsesIn.request(1)
          client.requestsOut.ensureSubscription()

          val serverRequest = server.expectRequest()
          serverRequest.request.attribute(Http2.streamId) should not be empty
          serverRequest.request.method shouldBe HttpMethods.POST
          serverRequest.request.header[headers.`Accept-Encoding`] should not be empty
          serverRequest.entityAsString shouldBe "ping"

          // now respond
          server.sendResponseFor(serverRequest, HttpResponse(entity = "pong"))

          val response = client.expectResponse()
          Unmarshal(response.entity).to[String].futureValue shouldBe "pong"
          response.attribute(requestIdAttr).get.id shouldBe "request-1"
        }
      }

      s"eventually fail (with backoff: ${withBackoff})" should {
        "when connecting keeps failing" inAssertAllStagesStopped new TestSetup(tls) {
          override def clientSettings = super.clientSettings
            .withTransport(ClientTransport.withCustomResolver((_, _) => {
              Future.successful(new InetSocketAddress("example.invalid", 80))
            })).mapHttp2Settings(changeSettings)

          client.sendRequest(
            HttpRequest(
              method = HttpMethods.POST,
              entity = "ping",
              headers = headers.`Accept-Encoding`(HttpEncodings.gzip) :: Nil
            )
              .addAttribute(requestIdAttr, RequestId("request-1"))
          )
          // need some demand on response side, otherwise, no requests will be pulled in
          client.responsesIn.request(1)
          if (withBackoff) {
            // not immediate when using backoff, 4 retries before failing, backoff is 300-800ms (so at least 1.2s)
            client.responsesIn.expectNoMessage(clientSettings.http2Settings.baseConnectionBackoff * 4)
          }
          client.responsesIn.expectError()
          client.requestsOut.expectCancellation()

        }
      }

    }

    reconnectionTests(false)
    reconnectionTests(true)

    "not hammer the service with reconnects when backoff is enabled" in new TestSetup(tls) {
      val probe = TestProbe()
      override def clientSettings: ClientConnectionSettings =
        super.clientSettings.withTransport(ClientTransport.withCustomResolver { (_, _) =>
          probe.ref ! "saw-reconnect"
          Future.successful(new InetSocketAddress("example.invalid", 80))
        }).mapHttp2Settings(_.withBaseConnectionBackoff(300.millis)
          .withMaxConnectionBackoff(1500.millis)
          .withMaxPersistentAttempts(4)
        )

      client.sendRequest(HttpRequest(method = HttpMethods.POST, uri = "/ping1", entity = "ping")
        .addAttribute(requestIdAttr, RequestId("request-1")))

      // need some demand on response side, otherwise, no requests will be pulled in
      client.responsesIn.request(1)

      // first try is immediate
      probe.expectMsg("saw-reconnect")
      probe.expectNoMessage(250.millis) // 300 ms - 600 ms (using slightly lower values than expected to have some slack when expectation is run a little late)
      probe.expectMsg("saw-reconnect")
      probe.expectNoMessage(550.millis) // 600 ms - 1200 ms
      probe.expectMsg("saw-reconnect")
      probe.expectNoMessage(700.millis) // 750 ms - 1500 ms // (capped at 750ms (half of 1500 ms) - 1500ms)
      probe.expectMsg("saw-reconnect")

      // max 4 attempts, giving up after that
      client.responsesIn.expectError()
      client.requestsOut.expectCancellation()
    }

    "not leak any stages if completed" should {
      "when waiting for a response" inAssertAllStagesStopped new TestSetup(tls) {
        client.sendRequest(
          HttpRequest(
            method = HttpMethods.POST,
            uri = "/ping1",
            entity = "ping",
            headers = headers.`Accept-Encoding`(HttpEncodings.gzip) :: Nil
          )
            .addAttribute(requestIdAttr, RequestId("ping"))
        )
        client.responsesIn.request(1)

        server.expectRequest()

        client.requestsOut.sendComplete()
        // await for all demux stages to timeout and complete (see completion-timeout on settings)
        Thread.sleep(150)
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

    /** Port of tcp connection as determined by remote-address attribute */
    def clientPort: Int = request.attribute(AttributeKeys.remoteAddress).get.getPort
  }

  class TestSetup(tls: Boolean) {
    def serverSettings: ServerSettings = ServerSettings(system)
    def clientSettings: ClientConnectionSettings =
      ClientConnectionSettings(system).withTransport(new ClientTransport {
        override def connectTo(host: String, port: Int, settings: ClientConnectionSettings)(implicit system: ActorSystem): Flow[ByteString, ByteString, Future[Http.OutgoingConnection]] = {
          Flow.fromGraph(KillSwitches.single[ByteString])
            .mapMaterializedValue { killer =>
              killProbe.ref ! killer
            }
            .viaMat(ClientTransport.TCP.connectTo(server.binding.localAddress.getHostString, server.binding.localAddress.getPort, settings)(system))(Keep.right)
        }
      })

    val killProbe = TestProbe()
    def killConnection(): Unit = killProbe.expectMsgType[UniqueKillSwitch].abort(new StreamTcpException("connection was killed"))

    object server {
      private lazy val requestProbe = TestProbe()
      private lazy val handler: HttpRequest => Future[HttpResponse] = { req =>
        val p = Promise[HttpResponse]()
        requestProbe.ref ! ServerRequest(req, p)
        p.future
      }
      lazy val binding = {
        val builder = Http().newServerAt("localhost", 0)
          .withSettings(serverSettings)

        (if (tls)
          builder.enableHttps(ExampleHttpContexts.exampleServerContext)
        else builder).bind(handler).futureValue
      }

      def expectRequest(): ServerRequest = requestProbe.expectMsgType[ServerRequest]
      def sendResponseFor(request: ServerRequest, response: HttpResponse): Unit =
        request.sendResponse(response)
    }
    object client {

      lazy val clientFlow = {
        val builder = Http().connectionTo("akka.example.org")
          .withCustomHttpsConnectionContext(ExampleHttpContexts.exampleClientContext)
          .withClientConnectionSettings(clientSettings)

        if (tls) builder.managedPersistentHttp2()
        else builder.managedPersistentHttp2WithPriorKnowledge()
      }

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
      // persistent connection should propagate close signal eventually

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
