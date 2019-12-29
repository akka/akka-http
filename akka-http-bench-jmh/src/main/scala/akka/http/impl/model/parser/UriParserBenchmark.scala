package akka.http.impl.model.parser

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri
import akka.parboiled2.UTF8
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import scala.concurrent.Await
import scala.concurrent.duration._

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@BenchmarkMode(Array(Mode.Throughput))
class UriParserBenchmark {
  implicit val system: ActorSystem = ActorSystem("uri-parser-benchmark")

  val url = "http://any.hostname?param1=111&amp;param2=222"

  @Setup
  def setup(): Unit = ()

  @TearDown
  def tearDown(): Unit = {
    Await.result(system.terminate(), 5.seconds)
  }

  @Benchmark
  def bench_parse_uri(bh: Blackhole): Unit = {
    bh.consume(Uri(url).query(UTF8, Uri.ParsingMode.Relaxed))
  }

}
