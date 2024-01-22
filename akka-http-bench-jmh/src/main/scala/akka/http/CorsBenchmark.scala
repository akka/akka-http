/*
 * Copyright (C) 2024 Lightbend Inc. <https://www.lightbend.com>
 * Copyright 2016 Lomig Mégard
 */

package akka.http

import akka.actor.ActorSystem
import akka.dispatch.ExecutionContexts
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.headers.HttpOrigin
import akka.http.scaladsl.model.headers.Origin
import akka.http.scaladsl.model.headers.`Access-Control-Request-Method`
import akka.http.scaladsl.model.HttpMethods
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.server.Directives
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.settings.CorsSettings
import com.typesafe.config.ConfigFactory
import org.openjdk.jmh.annotations._

import java.util.concurrent.TimeUnit
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.concurrent.Await
import scala.concurrent.ExecutionContext

/*
 * This benchmark is based on the akka-http-cors project by Lomig Mégard, licensed under the Apache License, Version 2.0.
 *
 * Reference results from run on Linux Gen 11, i5 2.60GHz:
 * Benchmark                         Mode  Cnt      Score     Error  Units
 * CorsBenchmark.baseline           thrpt   10  15352.913 ±  61.849  ops/s
 * CorsBenchmark.default_cors       thrpt   10  14097.737 ±  88.402  ops/s
 * CorsBenchmark.default_preflight  thrpt   10  13363.198 ± 243.895  ops/s
 */
@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.SECONDS)
@BenchmarkMode(Array(Mode.Throughput))
class CorsBenchmark extends Directives {
  private val config = ConfigFactory.parseString("akka.loglevel = ERROR").withFallback(ConfigFactory.load())

  implicit private val system: ActorSystem = ActorSystem("CorsBenchmark", config)
  implicit private val ec: ExecutionContext = scala.concurrent.ExecutionContext.global

  private val corsSettings = CorsSettings(system)

  private var baselineHandler: Function[HttpRequest, Future[HttpResponse]] = _
  private var corsDefaultHandler: Function[HttpRequest, Future[HttpResponse]] = _
  private var corsSettingsHandler: Function[HttpRequest, Future[HttpResponse]] = _
  private var request: HttpRequest = _
  private var requestCors: HttpRequest = _
  private var requestPreflight: HttpRequest = _

  @Setup
  def setup(): Unit = {
    baselineHandler = Route.toFunction(path("baseline") {
      get {
        complete("ok")
      }
    })
    corsDefaultHandler = Route.toFunction(path("cors") {
      cors() {
        get {
          complete("ok")
        }
      }
    })
    corsSettingsHandler = Route.toFunction(path("cors") {
      cors(corsSettings) {
        get {
          complete("ok")
        }
      }
    })

    val origin = Origin(HttpOrigin("http://example.com"))

    val base = s"http://127.0.0.1:8080"
    request = HttpRequest(uri = s"$base/baseline")
    requestCors = HttpRequest(
      method = HttpMethods.GET,
      uri = s"$base/cors",
      headers = List(origin)
    )
    requestPreflight = HttpRequest(
      method = HttpMethods.OPTIONS,
      uri = s"$base/cors",
      headers = List(origin, `Access-Control-Request-Method`(HttpMethods.GET))
    )
  }

  @TearDown
  def shutdown(): Unit = {
    Await.ready(system.terminate(), 5.seconds)
  }

  @Benchmark
  def baseline(): Unit = {
    assert(responseBody(baselineHandler(request)) == "ok")
  }

  @Benchmark
  def default_cors(): Unit = {
    assert(responseBody(corsDefaultHandler(requestCors)) == "ok")
  }

  @Benchmark
  def default_preflight(): Unit = {
    assert(responseBody(corsDefaultHandler(requestPreflight)) == "")
  }

  @Benchmark
  def settings_cors(): Unit = {
    assert(responseBody(corsSettingsHandler(requestCors)) == "ok")
  }

  @Benchmark
  def settings_preflight(): Unit = {
    assert(responseBody(corsSettingsHandler(requestPreflight)) == "")
  }

  private def responseBody(response: Future[HttpResponse]): String =
    Await.result(response.flatMap(_.entity.toStrict(3.seconds)).map(_.data.utf8String)(ExecutionContexts.parasitic), 3.seconds)
}
