package akka.http.impl.engine

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.event.NoLogging
import akka.http.CommonBenchmark
import akka.http.impl.engine.server.HttpServerBluePrint
import akka.http.scaladsl.model.ContentTypes
import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.headers
import akka.http.scaladsl.settings.ServerSettings
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.TLSPlacebo
import akka.util.ByteString
import com.typesafe.config.ConfigFactory
import org.openjdk.jmh.annotations._

@Warmup(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
class StreamServerProcessingBenchmark extends CommonBenchmark {
  val request = ByteString("GET / HTTP/1.1\r\nHost: localhost\r\nUser-Agent: test\r\n\r\n")

  // @Param - currently not a param
  val totalBytes: String = "1000000"

  @Param(Array("10", "100", "1000"))
  var numChunks: String = _

  var totalExpectedBytes: Long = _

  // @Param(Array("100")) -- not a param any more
  var numRequestsPerConnection: String = "100"

  @Param(Array("strict", "default", "chunked"))
  var entityType: String = _

  var httpFlow: Flow[ByteString, ByteString, Any] = _

  implicit var system: ActorSystem = _
  implicit var mat: ActorMaterializer = _

  @Benchmark
  def benchRequestProcessing(): Unit = {
    val latch = new CountDownLatch(1)
    Source.repeat(request)
      .take(numRequestsPerConnection.toInt)
      .via(httpFlow)
      .runWith(Sink.fold(0L)(_ + _.size))
      .onComplete { res =>
        latch.countDown()
        require(res.filter(_ >= totalExpectedBytes).isSuccess, s"Expected at least $totalExpectedBytes but only got $res")
      }(system.dispatcher)

    latch.await()
  }

  @Setup
  def setup(): Unit = {
    val config =
      ConfigFactory.parseString(
        """
           akka.actor.default-dispatcher.fork-join-executor.parallelism-max = 1
        """)
        .withFallback(ConfigFactory.load())
    system = ActorSystem("AkkaHttpBenchmarkSystem", config)
    mat = ActorMaterializer()

    val bytesPerChunk = totalBytes.toInt / numChunks.toInt
    totalExpectedBytes = numRequestsPerConnection.toInt * bytesPerChunk * numChunks.toInt

    val byteChunk = ByteString(new Array[Byte](bytesPerChunk))
    val streamedBytes = Source.repeat(byteChunk).take(numChunks.toInt)

    val entity = entityType match {
      case "strict" =>
        HttpEntity.Strict(ContentTypes.`application/octet-stream`, ByteString(new Array[Byte](bytesPerChunk.toInt * numChunks.toInt)))
      case "chunked" =>
        HttpEntity.Chunked.fromData(
          ContentTypes.`application/octet-stream`,
          streamedBytes
        )
      case "default" =>
        HttpEntity.Default(
          ContentTypes.`application/octet-stream`,
          bytesPerChunk.toInt * numChunks.toInt,
          streamedBytes
        )
    }

    val response = HttpResponse(
      headers = headers.Server("akka-http-bench") :: Nil,
      entity = entity
    )

    httpFlow =
      Flow[HttpRequest].map(_ => response) join
        (HttpServerBluePrint(ServerSettings(system), NoLogging, false) atop
          TLSPlacebo())
  }

  @TearDown
  def tearDown(): Unit = {
    system.terminate()
  }
}
