package akka.http.impl.engine

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.event.NoLogging
import akka.http.impl.engine.server.HttpServerBluePrint
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.headers
import akka.http.scaladsl.settings.ServerSettings
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.TLSPlacebo
import akka.util.ByteString
import com.typesafe.config.ConfigFactory
import org.openjdk.jmh.annotations._

import scala.concurrent.ExecutionContext

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.SECONDS)
@BenchmarkMode(Array(Mode.Throughput))
class ServerProcessingBenchmark {
  val request = ByteString("GET / HTTP/1.1\r\nHost: localhost\r\nUser-Agent: test\r\n\r\n")
  val response = HttpResponse(headers = headers.Server("akka-http-bench") :: Nil)

  var httpFlow: Flow[ByteString, ByteString, Any] = _
  implicit var system: ActorSystem = _
  implicit var ec: ExecutionContext = _
  implicit var mat: ActorMaterializer = _

  @Benchmark
  @OperationsPerInvocation(10000)
  def benchRequestProcessing(): Unit = {
    implicit val ec = system.dispatcher
    val numRequests = 10000
    val latch = new CountDownLatch(numRequests)
    Source.repeat(request)
      .take(numRequests)
      .via(httpFlow)
      .runForeach(_ => latch.countDown())

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
    ec = system.dispatcher
    mat = ActorMaterializer()
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
