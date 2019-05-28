/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.http.scaladsl.model.{ HttpResponse, StatusCodes }
import akka.http.scaladsl.{ Http, HttpConnectionContext, UseHttp2 }
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse, HttpProtocols }
import akka.stream.ActorMaterializer
import java.util.Base64
import akka.stream.scaladsl.{ Sink, Source, Tcp }
import akka.testkit.AkkaSpec
import akka.util.ByteString

import scala.concurrent.Future

class WithPriorKnowledgeSpec extends AkkaSpec("""
    akka.loglevel = warning
    akka.loggers = ["akka.testkit.TestEventListener"]
    akka.http.server.preview.enable-http2 = on
  """) {
  "An HTTP server with PriorKnowledge" should {
    implicit val mat = ActorMaterializer()

    val binding = Http().bindAndHandleAsync(
      _ ⇒ Future.successful(HttpResponse(status = StatusCodes.ImATeapot)),
      "127.0.0.1",
      port = 0,
      HttpConnectionContext(UseHttp2.PriorKnowledge)
    ).futureValue

    "respond to cleartext HTTP1.1 requests with cleartext HTTP1.1" in {
      val (host, port) = (binding.localAddress.getHostName, binding.localAddress.getPort)
      val responseFuture: Future[HttpResponse] = Http().singleRequest(HttpRequest(uri = s"http://$host:$port"))
      val response = responseFuture.futureValue
      response.protocol should be(HttpProtocols.`HTTP/1.1`)
      response.status should be(StatusCodes.ImATeapot)
    }

    "respond to cleartext HTTP2.0 requests with cleartext HTTP2.0" in {
      val (host, port) = (binding.localAddress.getHostName, binding.localAddress.getPort)
      val responseFuture: Future[HttpResponse] = Http().singleRequest(HttpRequest(uri = s"http://$host:$port"))

      val requestBytes = // Obtained by converting the input request bytes from curl with --http2-prior-knowledge
        "UFJJICogSFRUUC8yLjANCg0KU00NCg0KAAASBAAAAAAAAAMAAABkAARAAAAAAAIAAAAAAAAECAAAAAAAP/8AAQAAHgEFAAAAAYKEhkGKCJ1cC4Fw3HwAf3qIJbZQw6u20uBTAyovKg=="

      val sink =
        Source.single(requestBytes)
          .concat(Source.single("AAAABAEAAAAA"))
          .map(str => ByteString(Base64.getDecoder.decode(str)))
          .via(Tcp().outgoingConnection(host, port))
          .runWith(Sink.queue())
      val response = sink.pull().futureValue.get.map(_.toChar).mkString
      println(response)
    }
  }
}
