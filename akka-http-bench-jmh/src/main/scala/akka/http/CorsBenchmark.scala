/*
 * Copyright (C) 2024 Lightbend Inc. <https://www.lightbend.com>
 * Copyright 2016 Lomig Mégard
 */

package akka.http

import java.util.concurrent.TimeUnit
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.model.headers.{ HttpOrigin, Origin, `Access-Control-Request-Method` }
import akka.http.scaladsl.model.{ HttpMethods, HttpRequest }
import akka.http.scaladsl.server.Directives
import akka.http.scaladsl.settings.CorsSettings
import akka.http.scaladsl.unmarshalling.Unmarshal
import com.typesafe.config.ConfigFactory
import org.openjdk.jmh.annotations._

import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext }

// This benchmark is based on the akka-http-cors project by Lomig Mégard, licensed under the Apache License, Version 2.0.
@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.SECONDS)
@BenchmarkMode(Array(Mode.Throughput))
class CorsBenchmark extends Directives {
  private val config = ConfigFactory.parseString("akka.loglevel = ERROR").withFallback(ConfigFactory.load())

  implicit private val system: ActorSystem = ActorSystem("CorsBenchmark", config)
  implicit private val ec: ExecutionContext = scala.concurrent.ExecutionContext.global

  private val http = Http(system)
  private val corsSettings = CorsSettings(system)

  private var binding: ServerBinding = _
  private var request: HttpRequest = _
  private var requestCors: HttpRequest = _
  private var requestPreflight: HttpRequest = _

  @Setup
  def setup(): Unit = {
    val route = {
      path("baseline") {
        get {
          complete("ok")
        }
      } ~ path("cors") {
        cors(corsSettings) {
          get {
            complete("ok")
          }
        }
      }
    }
    val origin = Origin(HttpOrigin("http://example.com"))

    binding = Await.result(http.newServerAt("127.0.0.1", 0).bind(route), 1.second)
    val base = s"http://${binding.localAddress.getHostString}:${binding.localAddress.getPort}"

    request = HttpRequest(uri = base + "/baseline")
    requestCors = HttpRequest(
      method = HttpMethods.GET,
      uri = base + "/cors",
      headers = List(origin)
    )
    requestPreflight = HttpRequest(
      method = HttpMethods.OPTIONS,
      uri = base + "/cors",
      headers = List(origin, `Access-Control-Request-Method`(HttpMethods.GET))
    )
  }

  @TearDown
  def shutdown(): Unit = {
    val f = for {
      _ <- http.shutdownAllConnectionPools()
      _ <- binding.terminate(1.second)
      _ <- system.terminate()
    } yield ()
    Await.ready(f, 5.seconds)
  }

  @Benchmark
  def baseline(): Unit = {
    val f = http.singleRequest(request).flatMap(r => Unmarshal(r.entity).to[String])
    assert(Await.result(f, 1.second) == "ok")
  }

  @Benchmark
  def default_cors(): Unit = {
    val f = http.singleRequest(requestCors).flatMap(r => Unmarshal(r.entity).to[String])
    assert(Await.result(f, 1.second) == "ok")
  }

  @Benchmark
  def default_preflight(): Unit = {
    val f = http.singleRequest(requestPreflight).flatMap(r => Unmarshal(r.entity).to[String])
    assert(Await.result(f, 1.second) == "")
  }

}
