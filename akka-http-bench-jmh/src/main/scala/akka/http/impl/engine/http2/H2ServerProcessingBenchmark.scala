/*
 * Copyright (C) 2020-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.actor.ActorSystem
import akka.http.CommonBenchmark
import akka.http.impl.engine.http2.FrameEvent.{ DataFrame, HeadersFrame }
import akka.http.impl.engine.http2.framing.FrameRenderer
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpEntity.{ Chunk, LastChunk }
import akka.http.scaladsl.model.{ AttributeKeys, ContentTypes, HttpEntity, HttpResponse, Trailer }
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.settings.ServerSettings
import akka.stream.ActorMaterializer
import akka.stream.TLSProtocol.{ SslTlsInbound, SslTlsOutbound }
import akka.stream.scaladsl.{ Flow, Keep, Sink, Source }
import akka.util.ByteString
import com.typesafe.config.ConfigFactory
import org.openjdk.jmh.annotations._

import java.util.concurrent.{ CountDownLatch, TimeUnit }
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._

class H2ServerProcessingBenchmark extends CommonBenchmark {
  @Param(Array("empty", "singleframe"))
  var requestbody: String = _

  @Param(Array("strict", "closedelimited", "chunked"))
  var responsetype: String = _

  var response: HttpResponse = _

  var httpFlow: Flow[ByteString, ByteString, Any] = _
  implicit var system: ActorSystem = _
  implicit var mat: ActorMaterializer = _

  val packedResponse = ByteString(1, 5, 0, 0) // a HEADERS frame with end_stream == true

  val numRequests = 10000

  val requestBytes = ByteString("abcde")

  def requestWithoutBody(streamId: Int): ByteString =
    FrameRenderer.render(HeadersFrame(streamId, endStream = true, endHeaders = true, HPackSpecExamples.C41FirstRequestWithHuffman, None))
  def requestWithSingleFrameBody(streamId: Int): ByteString =
    FrameRenderer.render(HeadersFrame(streamId, endStream = false, endHeaders = true, HPackSpecExamples.C41FirstRequestWithHuffman, None)) ++
      FrameRenderer.render(DataFrame(streamId, endStream = true, requestBytes))

  var requestDataCreator: Int => ByteString = _

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
    requestDataCreator = requestbody match {
      case "empty"       => requestWithoutBody _
      case "singleframe" => requestWithSingleFrameBody _
    }

    val trailerHeader = RawHeader("grpc-status", "9")
    val responseBody = ByteString("hello")
    response = responsetype match {
      case "empty" =>
        HPackSpecExamples.FirstResponse
          .withEntity(HttpEntity.Empty)
          .addAttribute(AttributeKeys.trailer, Trailer(trailerHeader :: Nil))
      case "closedelimited" =>
        HPackSpecExamples.FirstResponse
          .withEntity(HttpEntity.CloseDelimited(ContentTypes.`text/plain(UTF-8)`, Source.single(responseBody)))
          .addAttribute(AttributeKeys.trailer, Trailer(trailerHeader :: Nil))
      case "chunked" =>
        HPackSpecExamples.FirstResponse
          .withEntity(HttpEntity.Chunked(ContentTypes.`text/plain(UTF-8)`, Source(Chunk(responseBody) :: LastChunk(trailer = trailerHeader :: Nil) :: Nil)))
      case "strict" =>
        HPackSpecExamples.FirstResponse
          .withEntity(HttpEntity.Strict(ContentTypes.`text/plain(UTF-8)`, responseBody))
          .addAttribute(AttributeKeys.trailer, Trailer(trailerHeader :: Nil))
    }
    val config =
      ConfigFactory.parseString(
        s"""
           akka.actor.default-dispatcher.fork-join-executor.parallelism-max = 1
           akka.http.server.http2.max-concurrent-streams = $numRequests # needs to be >= `numRequests`
           #akka.loglevel = debug
           #akka.http.server.log-unencrypted-network-bytes = 100
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
        .join(Http2Blueprint.serverStackTls(settings, log, NoOpTelemetry, Http().dateHeaderRendering))
    httpFlow = Http2.priorKnowledge(http1, http2)
  }

  @TearDown
  def tearDown(): Unit = {
    system.terminate()
  }
}
