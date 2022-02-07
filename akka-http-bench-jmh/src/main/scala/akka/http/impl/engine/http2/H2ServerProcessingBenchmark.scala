/*
 * Copyright (C) 2020-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.actor.ActorSystem
import akka.http.CommonBenchmark
import akka.http.impl.engine.server.ServerTerminator
import akka.http.scaladsl.Http
import akka.http.scaladsl.settings.ServerSettings
import akka.stream.ActorMaterializer
import akka.stream.TLSProtocol.{ SslTlsInbound, SslTlsOutbound }
import akka.stream.scaladsl.{ Flow, Keep, Sink, Source }
import akka.util.ByteString
import org.openjdk.jmh.annotations._

import java.util.concurrent.{ CountDownLatch, TimeUnit }
import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext, Future }

class H2ServerProcessingBenchmark extends CommonBenchmark with H2RequestResponseBenchmark {

  var httpFlow: Flow[ByteString, ByteString, Any] = _
  implicit var system: ActorSystem = _
  implicit var mat: ActorMaterializer = _

  val packedResponse = ByteString(1, 5, 0, 0) // a HEADERS frame with end_stream == true

  val numRequests = 10000

  @Benchmark
  @OperationsPerInvocation(10000) // should be same as numRequest
  def benchRequestProcessing(): Unit = {
    val latch = new CountDownLatch(numRequests)

    val requests =
      Source(Http2Protocol.ClientConnectionPreface +: Range(0, numRequests).map(i => requestDataCreator(1 + 2 * i)))
        .concatMat(Source.maybe)(Keep.right)

    val (in, done) =
      requests
        .viaMat(httpFlow)(Keep.left)
        .toMat(Sink.foreach(res => {
          // Skip headers/settings frames etc
          if (res.containsSlice(HPackSpecExamples.C61FirstResponseWithHuffman)
            || res.containsSlice(packedResponse)) {
            latch.countDown()
          }
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
    mat = ActorMaterializer()
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
    httpFlow = Http2.priorKnowledge(http1, http2)
  }

  @TearDown
  def tearDown(): Unit = {
    system.terminate()
  }
}
