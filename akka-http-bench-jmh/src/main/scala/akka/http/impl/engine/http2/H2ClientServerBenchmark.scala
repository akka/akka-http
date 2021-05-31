/*
 * Copyright (C) 2020-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.CommonBenchmark
import akka.http.impl.engine.http2.FrameEvent.HeadersFrame
import akka.http.impl.engine.http2.framing.FrameRenderer
import akka.http.scaladsl.client.RequestBuilding.Get
import akka.http.scaladsl.model.HttpEntity.LastChunk
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{ ContentTypes, HttpEntity, HttpRequest, HttpResponse }
import akka.http.scaladsl.settings.{ ClientConnectionSettings, ServerSettings }
import akka.stream.ActorMaterializer
import akka.stream.TLSProtocol.{ SslTlsInbound, SslTlsOutbound }
import akka.stream.scaladsl.{ BidiFlow, Flow, Keep, Sink, Source }
import akka.util.ByteString
import com.typesafe.config.ConfigFactory
import org.openjdk.jmh.annotations._

import java.util.concurrent.{ CountDownLatch, TimeUnit }
import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext, Future }

/**
 * Test converting a HttpRequest to bytes at the client and back to a request at the server, and vice-versa
 * for the response. Does not include the network.
 */
class H2ClientServerBenchmark extends CommonBenchmark {
  // Obtained by converting the input request bytes from curl with --http2-prior-knowledge
  def request(streamId: Int) =
    FrameRenderer.render(HeadersFrame(streamId, endStream = true, endHeaders = true, HPackSpecExamples.C41FirstRequestWithHuffman, None))

  @Param(Array("closedelimited", "chunked"))
  var responsetype: String = _

  var response: HttpResponse = _
  var httpFlow: Flow[HttpRequest, HttpResponse, Any] = _
  implicit var system: ActorSystem = _
  implicit var mat: ActorMaterializer = _

  val numRequests = 1000

  @Benchmark
  @OperationsPerInvocation(10000) // should be same as numRequest
  def benchRequestProcessing(): Unit = {
    implicit val ec: ExecutionContext = system.dispatcher

    val latch = new CountDownLatch(numRequests)

    val requests =
      Source.repeat(Get("/")).take(numRequests)
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
    response = responsetype match {
      case "closedelimited" => HPackSpecExamples.FirstResponse
      case "chunked" => HPackSpecExamples.FirstResponse.withEntity(
        HttpEntity.Chunked(ContentTypes.NoContentType, Source.single(LastChunk(trailer = immutable.Seq(RawHeader("grpc-status", "9")))))
      )
    }
    val config =
      ConfigFactory.parseString(
        s"""
           #akka.loglevel = debug
           akka.actor.default-dispatcher.fork-join-executor.parallelism-max = 1
           #akka.http.server.log-unencrypted-network-bytes = 100
           akka.http.server.http2.max-concurrent-streams = $numRequests # needs to be >= `numRequests`
         """)
        .withFallback(ConfigFactory.load())
    system = ActorSystem("AkkaHttpBenchmarkSystem", config)
    mat = ActorMaterializer()
    val settings = implicitly[ServerSettings]
    val log = system.log
    implicit val ec = system.dispatcher
    val http1 = Flow[SslTlsInbound].mapAsync(1)(_ => {
      Future.failed[SslTlsOutbound](new IllegalStateException("Failed h2 detection"))
    })
    val http2 =
      Http2Blueprint.handleWithStreamIdHeader(1)(req => {
        req.discardEntityBytes().future.map(_ => response)
      })(system.dispatcher)
        .join(Http2Blueprint.serverStackTls(settings, log, NoOpTelemetry))
    val server: Flow[ByteString, ByteString, NotUsed] = Http2.priorKnowledge(http1, http2)
    val client: BidiFlow[HttpRequest, ByteString, ByteString, HttpResponse, NotUsed] = Http2Blueprint.clientStack(ClientConnectionSettings(system), log, NoOpTelemetry)
    httpFlow = client.join(server)
  }

  @TearDown
  def tearDown(): Unit = {
    system.terminate()
  }
}
