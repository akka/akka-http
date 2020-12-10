package akka.http.impl.engine.http2

import akka.NotUsed
import akka.actor.ActorSystem
import akka.actor.ExtendedActorSystem
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
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.BidiFlow
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.testkit.TestProbe
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures

import java.util.UUID
import scala.concurrent.Future

object TestTelemetryImpl {
  @volatile var delegate: Option[TelemetrySpi] = None
}
class TestTelemetryImpl(system: ActorSystem) extends TelemetrySpi {
  import TestTelemetryImpl.delegate

  override def client: BidiFlow[HttpRequest, HttpRequest, HttpResponse, HttpResponse, NotUsed] = delegate.get.client
  override def server: BidiFlow[HttpResponse, HttpResponse, HttpRequest, HttpRequest, NotUsed] = delegate.get.server
}

class TelemetrySpiSpec extends AkkaSpecWithMaterializer(
  """
     akka.http.server.preview.enable-http2 = on
     akka.actor.serialize-messages = false
     akka.http.http2-telemetry-class = "akka.http.impl.engine.http2.TestTelemetryImpl"
  """) with ScalaFutures with BeforeAndAfterAll {

  case class RequestId(id: String) extends RequestResponseAssociation
  val requestIdAttr = AttributeKey[RequestId]("requestId")

  "Support for telemetry" should {

    "hook into HTTP2 client requests" in {
      val probe = TestProbe()
      TestTelemetryImpl.delegate = Some(new TelemetrySpi {
        override def client: BidiFlow[HttpRequest, HttpRequest, HttpResponse, HttpResponse, NotUsed] =
          BidiFlow.fromFlows(
            Flow[HttpRequest].map { req =>
              val reqId = RequestId(UUID.randomUUID().toString)
              probe.ref ! "req-seen"
              probe.ref ! reqId
              req.addAttribute(requestIdAttr, reqId).addHeader(headers.RawHeader("req-id", reqId.id))
            }.watchTermination() { (_, done) =>
              done.foreach(_ => probe.ref ! "close-seen")(system.dispatcher)
            },
            Flow[HttpResponse].map { res =>
              probe.ref ! "res-seen"
              probe.ref ! res.getAttribute(requestIdAttr).get
              res.removeAttribute(requestIdAttr)
            }
          ).mapMaterializedValue { _ =>
              probe.ref ! "seen-connection"
              NotUsed
            }

        override def server: BidiFlow[HttpResponse, HttpResponse, HttpRequest, HttpRequest, NotUsed] = BidiFlow.identity
      })

      val handler: HttpRequest => Future[HttpResponse] = { req =>
        req.headers.find(_.lowercaseName == "req-id").foreach(found => probe.ref ! found.value)
        Future.successful(HttpResponse())
      }
      val binding =
        Http().newServerAt("localhost", 0)
          .enableHttps(ExampleHttpContexts.exampleServerContext)
          .bind(handler).futureValue

      val http2flow = Http().connectionTo("akka.example.org")
        .withCustomHttpsConnectionContext(ExampleHttpContexts.exampleClientContext)
        .withClientConnectionSettings(ClientConnectionSettings(system).withTransport(ExampleHttpContexts.proxyTransport(binding.localAddress)))
        .http2()

      val (reqQueue, resQueue) =
        Source.queue(10, OverflowStrategy.fail)
          .viaMat(http2flow)(Keep.left)
          .toMat(Sink.actorRef(probe.ref, "done"))(Keep.both)
          .run()
      reqQueue.offer(HttpRequest())

      probe.expectMsg("seen-connection")
      probe.expectMsg("req-seen")
      val reqId = probe.expectMsgType[RequestId]
      val reqIdOnServer = probe.expectMsgType[String]
      reqIdOnServer should ===(reqId.id)

      probe.expectMsg("res-seen")
      val resId = probe.expectMsgType[RequestId]
      reqId should ===(resId)
      val res = probe.expectMsgType[HttpResponse]
      res.attribute(requestIdAttr) should be(None)
      reqQueue.complete()

      probe.expectMsg("close-seen")
    }

    "hook into HTTP2 server requests" in {
      val probe = TestProbe()
      val handler: HttpRequest => Future[HttpResponse] = { req =>
        req.headers.find(_.lowercaseName == "req-id").foreach(found => probe.ref ! found.value)
        Future.successful(HttpResponse())
      }
      TestTelemetryImpl.delegate = Some(new TelemetrySpi {
        override def client: BidiFlow[HttpRequest, HttpRequest, HttpResponse, HttpResponse, NotUsed] =
          BidiFlow.identity

        override def server: BidiFlow[HttpResponse, HttpResponse, HttpRequest, HttpRequest, NotUsed] = BidiFlow.fromFlows(
          Flow[HttpResponse].map { res =>
            probe.ref ! "res-seen"
            res
          }.watchTermination() { (_, done) =>
            done.foreach(_ => probe.ref ! "close-seen")(system.dispatcher)
          },
          StreamUtils.statefulAttrsMap[HttpRequest, HttpRequest](attrs =>

            { req =>
              probe.ref ! "req-seen"
              attrs.get[TelemetryAttributes.ConnectionMeta].foreach(probe.ref ! _)
              req
            }
          ).mapMaterializedValue { _ =>
            // FIXME how to find remote address here and pass context to request calls
            probe.ref ! "seen-connection"
            NotUsed
          })
      })

      val binding =
        Http().newServerAt("localhost", 0)
          .enableHttps(ExampleHttpContexts.exampleServerContext)
          .bind(handler).futureValue

      val http2flow = Http().connectionTo("akka.example.org")
        .withCustomHttpsConnectionContext(ExampleHttpContexts.exampleClientContext)
        .withClientConnectionSettings(ClientConnectionSettings(system).withTransport(ExampleHttpContexts.proxyTransport(binding.localAddress)))
        .http2()

      val resProbe = TestProbe()
      val (reqQueue, _) =
        Source.queue(10, OverflowStrategy.fail)
          .viaMat(http2flow)(Keep.left)
          .toMat(Sink.actorRef(resProbe.ref, "done"))(Keep.both)
          .run()
      reqQueue.offer(HttpRequest())

      probe.expectMsg("seen-connection")
      probe.expectMsg("req-seen")
      probe.expectMsgType[TelemetryAttributes.ConnectionMeta]

      probe.expectMsg("res-seen")
      val res = resProbe.expectMsgType[HttpResponse]
      reqQueue.complete()

      probe.expectMsg("close-seen")
      resProbe.expectMsg("done")
    }

  }

  override def afterTermination() = {
    TestTelemetryImpl.delegate = None
  }

}
