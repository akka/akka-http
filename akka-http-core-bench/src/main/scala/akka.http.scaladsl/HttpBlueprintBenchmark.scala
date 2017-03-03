/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.scaladsl

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.ActorMaterializer
import akka.stream.TLSProtocol.{SslTlsInbound, SslTlsOutbound}
import akka.stream.scaladsl.{Flow, Sink, Source}
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import scala.concurrent.Await
import scala.concurrent.duration._

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.SECONDS)
@BenchmarkMode(Array(Mode.Throughput))
class HttpBlueprintBenchmark {

  implicit val system = ActorSystem("HttpBlueprintBenchmark")
  implicit val materializer = ActorMaterializer()

  val serverLayer =
    Http().serverLayer()
      .join(Flow.fromSinkAndSource(Sink.ignore, Source.empty))
      .join(Flow.fromSinkAndSource(Sink.ignore, Source.empty))

  val serverHandler =
    Http().serverLayer().reversed
      .join(Flow.fromSinkAndSource[HttpRequest, HttpResponse](Sink.ignore, Source.empty))

  val serverConnection =
    Flow.fromSinkAndSource[SslTlsOutbound, SslTlsInbound](Sink.ignore, Source.empty)

  @Benchmark
  def build_server_layer(blackhole: Blackhole): Unit = {
    blackhole.consume(Http().serverLayer())
  }

  @Benchmark
  def run_server_layer(blackhole: Blackhole): Unit = {
    blackhole.consume(serverLayer.run())
  }

  @Benchmark
  def join_and_run_server_layer(blackhole: Blackhole): Unit = {
    blackhole.consume(serverHandler.join(serverConnection).run())
  }

  @TearDown
  def shutdown(): Unit = {
    Await.result(system.terminate(), 5.seconds)
  }

}