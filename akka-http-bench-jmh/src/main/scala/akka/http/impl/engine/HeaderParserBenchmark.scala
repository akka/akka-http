package akka.http.impl.engine

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.http.impl.engine.parsing.HttpHeaderParser
import akka.http.impl.settings.ParserSettingsImpl
import akka.http.impl.util.JavaMapping.MediaTypeFixedCharset
import akka.http.scaladsl.model.MediaType
import akka.util.ByteString
import com.typesafe.config.ConfigFactory
import org.openjdk.jmh.annotations._

import scala.concurrent.Await
import scala.concurrent.duration._

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@BenchmarkMode(Array(Mode.Throughput))
class HeaderParserBenchmark {
  implicit val system: ActorSystem = ActorSystem("header-parser-benchmark")

  @Param(Array("no", "yes"))
  var withCustomMediaTypes = "no"

  var parser: HttpHeaderParser = _

  val request = """GET / HTTP/1.1
      |
      |Content-Type: application/json
      |Accept: application/json, text/plain
      |Content-Length: 0
    """.stripMargin

  val firstHeaderStart = request.indexOf('\n') + 2

  val requestBytes = ByteString(request)

  @Setup
  def setup(): Unit = {
    parser = HttpHeaderParser.prime(HttpHeaderParser.unprimed(settings(), system.log, _ => ()))
  }

  private def settings() = {
    val root = ConfigFactory.load()
    val settings = ParserSettingsImpl.fromSubConfig(root, root.getConfig("akka.http.server.parsing"))
    if (withCustomMediaTypes == "no") settings
    else settings.withCustomMediaTypes(
      MediaType.customWithOpenCharset("application", "json")
    )
  }

  @TearDown
  def tearDown(): Unit = {
    Await.result(system.terminate(), 5.seconds)
  }

  @Benchmark
  def bench_parse_headers(): Int = {
    val next = parser.parseHeaderLine(requestBytes, firstHeaderStart)()
    parser.parseHeaderLine(requestBytes, next)()
  }
}
