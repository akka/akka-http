/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import java.util.Base64

import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ HttpProtocols, HttpRequest, HttpResponse, StatusCodes }
import akka.stream.{ ActorMaterializer, OverflowStrategy }
import akka.stream.scaladsl.{ Keep, Sink, SinkQueue, Source, Tcp }
import akka.testkit.AkkaSpec
import akka.util.ByteString

import scala.concurrent.Future

class WithPriorKnowledgeSpec extends AkkaSpec("""
    akka.loglevel = warning
    akka.loggers = ["akka.testkit.TestEventListener"]
    akka.http.server.preview.enable-http2 = on
  """) {

  implicit val ec = system.dispatcher

  "An HTTP server with PriorKnowledge" should {
    implicit val mat = ActorMaterializer()

    val binding = Http().bindAndHandleAsync(
      _ => Future.successful(HttpResponse(status = StatusCodes.ImATeapot)),
      "127.0.0.1",
      port = 0
    ).futureValue

    "respond to cleartext HTTP/1.1 requests with cleartext HTTP/1.1" in {
      val (host, port) = (binding.localAddress.getHostName, binding.localAddress.getPort)
      val responseFuture: Future[HttpResponse] = Http().singleRequest(HttpRequest(uri = s"http://$host:$port"))
      val response = responseFuture.futureValue
      response.protocol should be(HttpProtocols.`HTTP/1.1`)
      response.status should be(StatusCodes.ImATeapot)
    }

    "respond to cleartext HTTP/2 requests with cleartext HTTP/2" in {
      val (host, port) = (binding.localAddress.getHostName, binding.localAddress.getPort)

      val (source, sink) =
        Source.queue[String](1000, OverflowStrategy.fail)
          .map(str => ByteString(Base64.getDecoder.decode(str)))
          .via(Tcp().outgoingConnection(host, port))
          .toMat(Sink.queue())(Keep.both)
          .run()

      // Obtained by converting the input request bytes from curl with --http2-prior-knowledge
      // This includes port 9009 as 'authority', which our server accepts.
      source.offer("UFJJICogSFRUUC8yLjANCg0KU00NCg0KAAASBAAAAAAAAAMAAABkAARAAAAAAAIAAAAAAAAECAAAAAAAP/8AAQAAHgEFAAAAAYKEhkGKCJ1cC4Fw3HwAf3qIJbZQw6u20uBTAyovKg==").futureValue

      // read settings frame
      Http2Protocol.FrameType.byId(sink.pull().futureValue.get(3)) should be(Http2Protocol.FrameType.SETTINGS)
      // read settings frame
      Http2Protocol.FrameType.byId(sink.pull().futureValue.get(3)) should be(Http2Protocol.FrameType.SETTINGS)
      // ack settings
      source.offer("AAAABAEAAAAA")

      val response = readSink(sink).futureValue
      val tpe = Http2Protocol.FrameType.byId(response(3))
      tpe should be(Http2Protocol.FrameType.HEADERS)
      response.map(_.toChar).mkString should include("418")
    }
  }

  private def readSink(sink: SinkQueue[ByteString]): Future[ByteString] = {
    sink.pull().flatMap {
      case Some(bytes) if bytes.isEmpty =>
        readSink(sink)
      case Some(bytes) =>
        Future.successful(bytes)
    }
  }
}
