/*
 * Copyright (C) 2020-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine

import java.util.concurrent.CountDownLatch

import scala.concurrent.ExecutionContext

import akka.actor.ActorSystem
import akka.http.CommonBenchmark
import akka.http.scaladsl.model.{ ContentTypes, HttpEntity }
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.typesafe.config.ConfigFactory
import org.openjdk.jmh.annotations.{ Benchmark, Param, Setup, TearDown }

class HttpEntityBenchmark extends CommonBenchmark {
  @Param(Array("strict", "default"))
  var entityType: String = _

  implicit var system: ActorSystem = _

  var entity: HttpEntity = _

  @Benchmark
  def discardBytes(): Unit = {
    val latch = new CountDownLatch(1)
    entity.discardBytes()
      .future
      .onComplete(_ => latch.countDown())(ExecutionContext.parasitic)
    latch.await()
  }

  private val chunk = ByteString(new Array[Byte](10000))
  @Setup
  def setup(): Unit = {
    val config =
      ConfigFactory.parseString(
        """
           akka.actor.default-dispatcher.fork-join-executor.parallelism-max = 1
        """)
        .withFallback(ConfigFactory.load())
    system = ActorSystem("AkkaHttpBenchmarkSystem", config)

    entity = entityType match {
      case "strict" =>
        HttpEntity.Strict(ContentTypes.`application/octet-stream`, chunk)
      case "default" =>
        HttpEntity.Default(
          ContentTypes.`application/octet-stream`,
          10 * chunk.size,
          Source.repeat(chunk).take(10)
        )
    }
  }

  @TearDown
  def tearDown(): Unit = system.terminate()
}
