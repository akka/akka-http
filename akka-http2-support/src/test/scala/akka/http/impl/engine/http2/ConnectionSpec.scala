/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import scala.concurrent.duration._
import akka.http.impl.util.{ AkkaSpecWithMaterializer, ExampleHttpContexts }
import akka.http.scaladsl.{ Http, HttpConnectionContext }
import akka.http.scaladsl.client.RequestBuilding.Get
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.server.Directives.complete
import akka.http.scaladsl.settings.ClientConnectionSettings
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{ Keep, Sink, Source }
import akka.util.ByteString
import org.scalatest.concurrent.ScalaFutures

class ConnectionSpec extends AkkaSpecWithMaterializer(
  """akka.http.server.preview.enable-http2 = on
    |""".stripMargin) with ScalaFutures {
  val handler = complete(OK, "Test successful")

  "The HTTP/2 connection API" should {
    "establish a secure connection" in {
      val binding =
        Http().newServerAt("localhost", 0).enableHttps(ExampleHttpContexts.exampleServerContext).bind(handler).futureValue

      val clientSettings = ClientConnectionSettings(system).withTransport(ExampleHttpContexts.proxyTransport(binding.localAddress))

      val (requests, responses) = Source.queue(10, OverflowStrategy.fail)
        .via(Http2().outgoingConnection("akka.example.org", 443, clientSettings, ExampleHttpContexts.exampleClientContext))
        .toMat(Sink.queue[HttpResponse])(Keep.both)
        .run()

      requests.offer(Get("/"))
      val entity = responses.pull().futureValue.get.entity.toStrict(500.millis).futureValue
      entity.data shouldBe ByteString("Test successful")
    }

    "establish an insecure connection with prior knowledge" in {
      val binding = Http().newServerAt("localhost", 0).bind(handler).futureValue

      val clientSettings = ClientConnectionSettings(system).withTransport(ExampleHttpContexts.proxyTransport(binding.localAddress))

      val (requests, responses) = Source.queue(100, OverflowStrategy.fail)
        .via(Http2().outgoingConnection("localhost", binding.localAddress.getPort, clientSettings, HttpConnectionContext))
        .toMat(Sink.queue[HttpResponse])(Keep.both)
        .run()

      requests.offer(Get("/"))

      val entity = responses.pull().futureValue.get.entity.toStrict(500.millis).futureValue
      entity.data shouldBe ByteString("Test successful")
    }
  }
}
