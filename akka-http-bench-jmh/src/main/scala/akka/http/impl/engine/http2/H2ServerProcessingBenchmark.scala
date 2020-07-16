package akka.http.impl.engine.http2

import java.util.concurrent.CountDownLatch

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration._
import akka.actor.ActorSystem
import akka.http.CommonBenchmark
import akka.http.impl.engine.http2.FrameEvent.HeadersFrame
import akka.http.impl.engine.http2.framing.FrameRenderer
import akka.http.scaladsl.Http2
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.settings.ServerSettings
import akka.stream.ActorMaterializer
import akka.stream.TLSProtocol.{ SslTlsInbound, SslTlsOutbound }
import akka.stream.scaladsl.{ Flow, Keep, Sink, Source }
import akka.util.ByteString
import com.typesafe.config.ConfigFactory
import org.openjdk.jmh.annotations._

class H2ServerProcessingBenchmark extends CommonBenchmark {
  // Obtained by converting the input request bytes from curl with --http2-prior-knowledge
  def request(streamId: Int) =
    FrameRenderer.render(HeadersFrame(streamId, endStream = true, endHeaders = true, HPackSpecExamples.C41FirstRequestWithHuffman, None))

  val response: HttpResponse = HPackSpecExamples.FirstResponse

  var httpFlow: Flow[ByteString, ByteString, Any] = _
  implicit var system: ActorSystem = _
  implicit var mat: ActorMaterializer = _

  val packedResponse = ByteString(-62, -63, -64, -65, -66)

  @Benchmark
  @OperationsPerInvocation(10000)
  def benchRequestProcessing(): Unit = {
    val numRequests = 10000
    val latch = new CountDownLatch(numRequests)

    val requests =
      Source(Http2Protocol.ClientConnectionPreface +: Range(0, numRequests).map(i => request(1 + 2 * i)))
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

    latch.await()

    in.success(None)
    Await.result(done, 1.hour)
  }

  @Setup
  def setup(): Unit = {
    val config =
      ConfigFactory.parseString(
        """
           #akka.loglevel = debug
           akka.actor.default-dispatcher.fork-join-executor.parallelism-max = 1
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
        .join(Http2Blueprint.serverStackTls(settings, log))
    httpFlow = Http2.priorKnowledge(http1, http2)
  }

  @TearDown
  def tearDown(): Unit = {
    system.terminate()
  }
}
