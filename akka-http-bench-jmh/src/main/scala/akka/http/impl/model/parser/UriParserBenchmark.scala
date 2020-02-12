package akka.http.impl.model.parser

import java.util.concurrent.TimeUnit

import akka.http.scaladsl.model.Uri
import akka.parboiled2.UTF8
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@BenchmarkMode(Array(Mode.Throughput))
class UriParserBenchmark {

  @Param(Array(
    "http://any.hostname?param1=111&amp;param2=222",
    "http://any.hostname?param1=111&amp;param2=222&param3=333&param4=444&param5=555&param6=666&param7=777&param8=888&param9=999"
  ))
  var url = ""

  @Benchmark
  def bench_parse_uri(bh: Blackhole): Unit = {
    bh.consume(Uri(url).query(UTF8, Uri.ParsingMode.Relaxed))
  }

}
