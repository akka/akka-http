/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.impl.util.AkkaSpecWithMaterializer
import akka.http.impl.util.ExampleHttpContexts
import akka.http.impl.util.StreamUtils
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.AttributeKey
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.RequestResponseAssociation
import akka.http.scaladsl.model.headers
import akka.http.scaladsl.settings.ClientConnectionSettings
import akka.stream.Attributes
import akka.stream.Attributes.Attribute
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.BidiFlow
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.Tcp
import akka.testkit.TestKit
import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures

import java.util.UUID
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success

object TestTelemetryImpl {
  @volatile var delegate: Option[TelemetrySpi] = None
}
class TestTelemetryImpl(system: ActorSystem) extends TelemetrySpi {
  import TestTelemetryImpl.delegate

  override def client: BidiFlow[HttpRequest, HttpRequest, HttpResponse, HttpResponse, NotUsed] = delegate.get.client
  override def serverBinding: Flow[Tcp.IncomingConnection, Tcp.IncomingConnection, NotUsed] = delegate.get.serverBinding
  override def serverConnection: BidiFlow[HttpResponse, HttpResponse, HttpRequest, HttpRequest, NotUsed] = delegate.get.serverConnection

}

class TelemetrySpiPlaintextSpec extends TelemetrySpiSpec(false)
class TelemetrySpiCypherSpec extends TelemetrySpiSpec(true)

abstract class TelemetrySpiSpec(useTls: Boolean) extends AkkaSpecWithMaterializer(
  """
     akka.http.server.preview.enable-http2 = on
     akka.actor.serialize-messages = false
     akka.http.http2-telemetry-class = "akka.http.impl.engine.http2.TestTelemetryImpl"
  """) with ScalaFutures with BeforeAndAfterAll {

  case class RequestId(id: String) extends RequestResponseAssociation
  val requestIdAttr = AttributeKey[RequestId]("requestId")

  case class ConnectionId(id: String) extends Attribute

  def bindAndConnect(probe: TestProbe): (Http.ServerBinding, Flow[HttpRequest, HttpResponse, Future[Http.OutgoingConnection]]) = {
    val handler: HttpRequest => Future[HttpResponse] = { request =>
      request.headers.find(_.lowercaseName == "request-id").foreach(found => probe.ref ! found.value)
      Future.successful(HttpResponse())
    }

    val serverBinding =
      if (useTls) {
        Http().newServerAt("localhost", 0)
          .enableHttps(ExampleHttpContexts.exampleServerContext)
          .bind(handler).futureValue
      } else {
        Http().newServerAt("localhost", 0)
          .bind(handler).futureValue
      }

    val http2ClientFlow =
      if (useTls) {
        Http().connectionTo("akka.example.org")
          .withCustomHttpsConnectionContext(ExampleHttpContexts.exampleClientContext)
          .withClientConnectionSettings(ClientConnectionSettings(system).withTransport(ExampleHttpContexts.proxyTransport(serverBinding.localAddress)))
          .http2()
      } else {
        Http().connectionTo("akka.example.org")
          .withClientConnectionSettings(ClientConnectionSettings(system).withTransport(ExampleHttpContexts.proxyTransport(serverBinding.localAddress)))
          .http2WithPriorKnowledge()
      }

    (serverBinding, http2ClientFlow)
  }

  "Support for telemetry" should {

    "hook into HTTP2 client requests" in {
      val probe = TestProbe()
      TestTelemetryImpl.delegate = Some(new TelemetrySpi {
        override def client: BidiFlow[HttpRequest, HttpRequest, HttpResponse, HttpResponse, NotUsed] =
          BidiFlow.fromFlows(
            StreamUtils.statefulAttrsMap[HttpRequest, HttpRequest] { attrs => request =>
              val requestId = RequestId(UUID.randomUUID().toString)
              probe.ref ! "request-seen"
              attrs.get[TelemetryAttributes.ClientMeta].foreach(probe.ref ! _)
              probe.ref ! requestId
              request.addAttribute(requestIdAttr, requestId).addHeader(headers.RawHeader("request-id", requestId.id))
            }.watchTermination() { (_, done) =>
              done.onComplete {
                case Success(_) => probe.ref ! "close-seen" // this is the expected case
                case Failure(t) => probe.ref ! t.getMessage // useful to diagnose cases where there's a failure
              }(system.dispatcher)

            },
            Flow[HttpResponse].map { response =>
              probe.ref ! "response-seen"
              probe.ref ! response.getAttribute(requestIdAttr).get
              response.removeAttribute(requestIdAttr)
            }
          ).mapMaterializedValue { _ =>
              probe.ref ! "seen-connection"
              NotUsed
            }

        override def serverBinding: Flow[Tcp.IncomingConnection, Tcp.IncomingConnection, NotUsed] = Flow[Tcp.IncomingConnection]
        override def serverConnection: BidiFlow[HttpResponse, HttpResponse, HttpRequest, HttpRequest, NotUsed] = BidiFlow.identity
      })

      val (serverBinding, http2ClientFow) = bindAndConnect(probe)

      val (requestQueue, _) =
        Source.queue(10, OverflowStrategy.fail)
          .viaMat(http2ClientFow)(Keep.left)
          .toMat(Sink.actorRef(probe.ref, "done"))(Keep.both)
          .run()
      requestQueue.offer(HttpRequest())

      probe.expectMsg("seen-connection")
      probe.expectMsg("request-seen")
      probe.expectMsgType[TelemetryAttributes.ClientMeta]
      val requestId = probe.expectMsgType[RequestId]
      val requestIdOnServer = probe.expectMsgType[String]
      requestIdOnServer should ===(requestId.id)

      probe.expectMsg("response-seen")
      val responseId = probe.expectMsgType[RequestId]
      requestId should ===(responseId)
      val response = probe.expectMsgType[HttpResponse]
      response.attribute(requestIdAttr) should be(None)
      requestQueue.complete()

      probe.expectMsg("close-seen")
      serverBinding.terminate(3.seconds).futureValue
    }

    "hook into HTTP2 server requests" in {
      val telemetryProbe = TestProbe()
      TestTelemetryImpl.delegate = Some(new TelemetrySpi {
        override def client: BidiFlow[HttpRequest, HttpRequest, HttpResponse, HttpResponse, NotUsed] =
          BidiFlow.identity

        override def serverBinding: Flow[Tcp.IncomingConnection, Tcp.IncomingConnection, NotUsed] = Flow[Tcp.IncomingConnection]
          .mapMaterializedValue { _ =>
            telemetryProbe.ref ! "bind-seen"
            NotUsed
          }
          .map { conn =>
            val connId = ConnectionId(UUID.randomUUID().toString)
            telemetryProbe.ref ! "connection-seen"
            telemetryProbe.ref ! connId
            conn.copy(flow = conn.flow.addAttributes(Attributes(connId)))
          }.watchTermination() { (notUsed, done) =>
            done.onComplete(_ => telemetryProbe.ref ! "unbind-seen")(system.dispatcher)
            notUsed
          }

        override def serverConnection: BidiFlow[HttpResponse, HttpResponse, HttpRequest, HttpRequest, NotUsed] = BidiFlow.fromFlows(
          Flow[HttpResponse].map { response =>
            telemetryProbe.ref ! "response-seen"
            response
          }.watchTermination() { (_, done) =>
            done.foreach(_ => telemetryProbe.ref ! "close-seen")(system.dispatcher)
          },
          StreamUtils.statefulAttrsMap[HttpRequest, HttpRequest](attributes =>
            { request =>
              telemetryProbe.ref ! "request-seen"
              attributes.get[ConnectionId].foreach(telemetryProbe.ref ! _)
              request
            }
          ))
      })

      val (serverBinding, http2ClientFlow) = bindAndConnect(telemetryProbe)
      telemetryProbe.expectMsg("bind-seen")

      val responseProbe = TestProbe()
      val (requestQueue, _) =
        Source.queue(10, OverflowStrategy.fail)
          .viaMat(http2ClientFlow)(Keep.left)
          .toMat(Sink.actorRef(responseProbe.ref, "onCompleteMessage"))(Keep.both)
          .run()
      requestQueue.offer(HttpRequest())

      // A new connection happens...
      telemetryProbe.expectMsg("connection-seen")
      val connId = telemetryProbe.expectMsgType[ConnectionId]
      // ... and a request flies in via _that_ connection.
      telemetryProbe.expectMsg("request-seen")
      telemetryProbe.expectMsgType[ConnectionId] should ===(connId)

      // The server sends the response...
      telemetryProbe.expectMsg("response-seen")
      // .. which the client consumes...
      val response = responseProbe.expectMsgType[HttpResponse]
      response.discardEntityBytes()
      // ... and then the client completes.
      requestQueue.complete()

      // As a consequence the whole stream completes.
      telemetryProbe.expectMsg("close-seen")
      responseProbe.expectMsg("onCompleteMessage")

      serverBinding.terminate(3.seconds).futureValue
      telemetryProbe.expectMsg("unbind-seen")
    }

    "fallback if impl class cannot be found" in {
      val system = ActorSystem(s"${getClass.getSimpleName}-noImplFound", ConfigFactory.parseString(
        s"""
            akka.http.http2-telemetry-class = no.such.Clazz
          """))
      try {
        TelemetrySpi.create(system) should ===(NoOpTelemetry)
      } finally {
        TestKit.shutdownActorSystem(system)
      }
    }

  }

  override def afterTermination() = {
    TestTelemetryImpl.delegate = None
  }

}
