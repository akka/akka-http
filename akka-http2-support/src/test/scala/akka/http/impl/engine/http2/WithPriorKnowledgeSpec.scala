/*
 * Copyright (C) 2019-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import java.util.Base64

import akka.http.impl.util.AkkaSpecWithMaterializer
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ HttpProtocols, HttpRequest, HttpResponse, StatusCodes }
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.{ Keep, Source, Tcp }
import akka.util.ByteString

import scala.concurrent.Future

class WithPriorKnowledgeSpec extends AkkaSpecWithMaterializer("""
    akka.http.server.enable-http2 = on
    akka.http.server.http2.log-frames = on
  """) {

  "An HTTP server with PriorKnowledge" should {
    val binding = Http().newServerAt("127.0.0.1", 0).bind(_ => Future.successful(HttpResponse(status = StatusCodes.ImATeapot))).futureValue

    "respond to cleartext HTTP/1.1 requests with cleartext HTTP/1.1" in {
      val (host, port) = (binding.localAddress.getHostName, binding.localAddress.getPort)
      val responseFuture: Future[HttpResponse] = Http().singleRequest(HttpRequest(uri = s"http://$host:$port"))
      val response = responseFuture.futureValue
      response.protocol should be(HttpProtocols.`HTTP/1.1`)
      response.status should be(StatusCodes.ImATeapot)
    }

    "respond to cleartext HTTP/2 requests with cleartext HTTP/2" in {
      val (host, port) = (binding.localAddress.getHostName, binding.localAddress.getPort)

      val fromServer = Http2FrameProbe()

      val source =
        Source.queue[String](1000, OverflowStrategy.fail)
          .map(str => ByteString(Base64.getDecoder.decode(str)))
          .via(Tcp(system).outgoingConnection(host, port))
          .toMat(fromServer.sink)(Keep.left)
          .run()

      // Obtained by converting the input request bytes from curl with --http2-prior-knowledge
      // This includes port 9009 as 'authority', which our server accepts.
      source.offer("UFJJICogSFRUUC8yLjANCg0KU00NCg0KAAASBAAAAAAAAAMAAABkAARAAAAAAAIAAAAAAAAECAAAAAAAP/8AAQAAHgEFAAAAAYKEhkGKCJ1cC4Fw3HwAf3qIJbZQw6u20uBTAyovKg==").futureValue

      fromServer.expectFrameFlagsAndPayload(Http2Protocol.FrameType.SETTINGS, 0) // don't check data
      fromServer.expectSettingsAck()

      // ack settings
      source.offer("AAAABAEAAAAA")

      fromServer.expectHeaderBlock(1, true)

      source.complete()
      fromServer.plainDataProbe.expectComplete()
    }

    "respond to cleartext HTTP/2 requests with cleartext HTTP/2 (connection level client API)" in {
      val connectionFlow = Http().connectionTo(binding.localAddress.getHostName).toPort(binding.localAddress.getPort).http2WithPriorKnowledge()

      val (queue, headFuture) = Source.queue(1000, OverflowStrategy.fail)
        .via(connectionFlow)
        .toMat(Sink.headOption)(Keep.both)
        .run()
      queue.offer(HttpRequest())
      val head = headFuture.futureValue
      head.isEmpty should be(false)
      val response = head.get
      response.entity.discardBytes()
      response.status should ===(StatusCodes.ImATeapot)
      queue.complete()
    }
  }
}
