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
class ServerProcessingBenchmark2 extends CommonBenchmark {
  val request = ByteString("GET / HTTP/1.1\r\nHost: localhost\r\nUser-Agent: test\r\n\r\n")

  @Param(Array("100"))
  var bytesPerChunk: String = _

  @Param(Array("10", "100", "1000"))
  var numChunks: String = _

  @Param(Array("100"))
  var numRequestsPerConnection: String = _

  @Param(Array("strict", "default", "chunked"))
  var entityType: String = _

  var httpFlow: Flow[ByteString, ByteString, Any] = _

  implicit var system: ActorSystem = _
  implicit var mat: ActorMaterializer = _

  @Benchmark
  @OperationsPerInvocation(1)
  def benchRequestProcessing(): Unit = {
    val latch = new CountDownLatch(1)
    Source.repeat(request)
      .take(numRequestsPerConnection.toInt)
      .via(httpFlow)
      .runWith(Sink.fold(0L)(_ + _.size))
      .onComplete { res =>
        latch.countDown()
        val expectedSize = bytesPerChunk.toLong * numChunks.toLong * numRequestsPerConnection.toInt
        require(res.filter(_ >= expectedSize).isSuccess, s"Expected at least $expectedSize but only got $res")
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

    val byteChunk = ByteString(new Array[Byte](bytesPerChunk.toInt))
    val streamedBytes = Source.repeat(byteChunk).take(numChunks.toInt) // 1MB of data

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
