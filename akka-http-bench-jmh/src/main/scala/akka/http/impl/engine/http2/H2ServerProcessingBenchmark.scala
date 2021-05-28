/*
 * Copyright (C) 2020-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import java.util.concurrent.{ CountDownLatch, TimeUnit }
import scala.concurrent.Await
import scala.concurrent.duration._
import akka.actor.ActorSystem
import akka.http.CommonBenchmark
import akka.http.scaladsl.client.RequestBuilding.Get
import akka.http.scaladsl.model.HttpEntity.LastChunk
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{ ContentTypes, HttpEntity, HttpRequest, HttpResponse }
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Flow, Keep, Sink, Source }
import com.typesafe.config.ConfigFactory
import org.openjdk.jmh.annotations._

import scala.util.{ Failure, Success }

class H2ServerProcessingBenchmark extends CommonBenchmark {

  @Param(Array("closedelimited", "chunked"))
  var responsetype: String = _

  var response: HttpResponse = _

  var httpFlow: Flow[HttpRequest, HttpResponse, Any] = _
  implicit var system: ActorSystem = _
  implicit var mat: ActorMaterializer = _

  val numRequests = 10000

  @Benchmark
  @OperationsPerInvocation(10000) // should be same as numRequest
  def benchRequestProcessing(): Unit = {
    implicit val ec = system.dispatcher

    val latch = new CountDownLatch(numRequests)

    val requests =
      Source.repeat(Get("/")).take(numRequests)
        .concatMat(Source.maybe)(Keep.right)

    val (in, done) =
      requests
        .viaMat(httpFlow)(Keep.left)
        .toMat(Sink.foreach(res => {
          res.entity.dataBytes.runWith(Sink.ignore).onComplete {
            case Success(_) => latch.countDown()
            case Failure(e) => throw e
          }
        }))(Keep.both)
        .run()

    require(latch.await(10, TimeUnit.SECONDS), "Not all responses were received in time")

    in.success(None)
    Await.result(done, 10.seconds)
  }

  @Setup
  def setup(): Unit = {
    response = responsetype match {
      case "closedelimited" => HPackSpecExamples.FirstResponse
      case "chunked" => HPackSpecExamples.FirstResponse.withEntity(
        HttpEntity.Chunked(ContentTypes.NoContentType, Source.single(LastChunk(trailer = Seq(RawHeader("grpc-status", "9")))))
      )
    }
    val config =
      ConfigFactory.parseString(
        s"""
           #akka.loglevel = debug
           akka.actor.default-dispatcher.fork-join-executor.parallelism-max = 1
           #akka.http.server.log-unencrypted-network-bytes = 100
           akka.http.server.http2.max-concurrent-streams = $numRequests # needs to be >= `numRequests`
         """)
        .withFallback(ConfigFactory.load())
    system = ActorSystem("AkkaHttpBenchmarkSystem", config)
    mat = ActorMaterializer()
    implicit val ec = system.dispatcher
    httpFlow = Http2Blueprint.handleWithStreamIdHeader(1)(req => {
      req.discardEntityBytes().future.map(_ => response)
    })(system.dispatcher)
  }

  @TearDown
  def tearDown(): Unit = {
    system.terminate()
  }
}
