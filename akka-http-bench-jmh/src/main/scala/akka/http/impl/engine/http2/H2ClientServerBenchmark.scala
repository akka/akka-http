/*
 * Copyright (C) 2020-2025 Lightbend Inc. <https://akka.io>
 */

package akka.http.impl.engine.http2

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.CommonBenchmark
import akka.http.impl.engine.server.ServerTerminator
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse }
import akka.http.scaladsl.settings.{ ClientConnectionSettings, ServerSettings }
import akka.stream.TLSProtocol.{ SslTlsInbound, SslTlsOutbound }
import akka.stream.scaladsl.{ BidiFlow, Flow, Keep, Sink, Source }
import akka.util.ByteString
import org.openjdk.jmh.annotations._

import java.util.concurrent.{ CountDownLatch, TimeUnit }
import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext, Future }

/**
 * Test converting a HttpRequest to bytes at the client and back to a request at the server, and vice-versa
 * for the response. Does not include the network.
 */
class H2ClientServerBenchmark extends CommonBenchmark with H2RequestResponseBenchmark {
  var httpFlow: Flow[HttpRequest, HttpResponse, Any] = _
  implicit var system: ActorSystem = _

  val numRequests = 1000

  @Benchmark
  @OperationsPerInvocation(1000) // should be same as numRequest
  def benchRequestProcessing(): Unit = {
    implicit val ec: ExecutionContext = system.dispatcher

    val latch = new CountDownLatch(numRequests)

    val requests =
      Source.repeat(request).take(numRequests)
        .concatMat(Source.maybe)(Keep.right)

    val (in, done) =
      requests
        .viaMat(httpFlow)(Keep.left)
        .toMat(Sink.foreach(res => {
          res.discardEntityBytes().future.onComplete(_ => latch.countDown())
        }))(Keep.both)
        .run()

    require(latch.await(10, TimeUnit.SECONDS), "Not all responses were received in time")

    in.success(None)
    Await.result(done, 10.seconds)
  }

  @Setup
  def setup(): Unit = {
    initRequestResponse()

    system = ActorSystem("AkkaHttpBenchmarkSystem", config)
    val settings = implicitly[ServerSettings]
    val log = system.log
    implicit val ec = system.dispatcher
    val http1 = Flow[SslTlsInbound].mapAsync(1)(_ => {
      Future.failed[SslTlsOutbound](new IllegalStateException("Failed h2 detection"))
    }).mapMaterializedValue(_ => new ServerTerminator {
      override def terminate(deadline: FiniteDuration)(implicit ex: ExecutionContext): Future[Http.HttpTerminated] = ???
    })
    val http2 =
      Http2Blueprint.handleWithStreamIdHeader(1)(req => {
        req.discardEntityBytes().future.map(_ => response)
      })(system.dispatcher)
        .joinMat(Http2Blueprint.serverStackTls(settings, log, NoOpTelemetry, Http().dateHeaderRendering))(Keep.right)
    val server: Flow[ByteString, ByteString, Any] = Http2.priorKnowledge(http1, http2)
    val client: BidiFlow[HttpRequest, ByteString, ByteString, HttpResponse, NotUsed] = Http2Blueprint.clientStack(ClientConnectionSettings(system), log, NoOpTelemetry)
    httpFlow = client.join(server)
  }

  @TearDown
  def tearDown(): Unit = {
    system.terminate()
  }
}
