/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.scaladsl

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Flow, Sink, Source }
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import scala.concurrent.Await
import scala.concurrent.duration._

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.SECONDS)
@BenchmarkMode(Array(Mode.Throughput))
class HttpBlueprintBenchmark {

  implicit val system = ActorSystem("HttpBlueprintBuildBenchmark")
  implicit val materializer = ActorMaterializer()

  val serverLayer =
    Http().serverLayer()
      .join(Flow.fromSinkAndSource(Sink.ignore, Source.empty))
      .join(Flow.fromSinkAndSource(Sink.ignore, Source.empty))

  @Benchmark
  def build_server_layer(blackhole: Blackhole): Unit = {
    blackhole.consume(Http().serverLayer())
  }

  @Benchmark
  def run_server_layer(blackhole: Blackhole): Unit = {
    blackhole.consume(serverLayer.run())
  }

  @TearDown
  def shutdown(): Unit = {
    Await.result(system.terminate(), 5.seconds)
  }

}