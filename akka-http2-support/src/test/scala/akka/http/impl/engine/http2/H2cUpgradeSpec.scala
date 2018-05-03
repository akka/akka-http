/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.http.impl.engine.http2.FrameEvent.SettingsFrame
import akka.http.impl.engine.http2.Http2Protocol.Flags
import akka.http.impl.engine.http2.framing.Http2FrameParsing.readSettings
import akka.http.scaladsl.model.{ HttpResponse, StatusCodes }
import akka.http.scaladsl.{ Http2, HttpConnectionContext }
import akka.stream.ActorMaterializer
import akka.stream.impl.io.ByteStringParser.ByteReader
import akka.stream.scaladsl.{ Sink, Source, Tcp }
import akka.testkit.AkkaSpec
import akka.util.ByteString

import scala.concurrent.Future
import scala.concurrent.duration._

class H2cUpgradeSpec extends AkkaSpec("""
    akka.loglevel = warning
    akka.loggers = ["akka.testkit.TestEventListener"]
    akka.http.server.preview.enable-http2 = on
  """) {

  override implicit val patience = PatienceConfig(5.seconds, 5.seconds)

  "An HTTP/1.1 server without TLS that allows upgrading to cleartext HTTP/2" should {
    implicit val mat = ActorMaterializer()

    val binding = Http2().bindAndHandleAsync(
      _ => Future.successful(HttpResponse(status = StatusCodes.ImATeapot)),
      "127.0.0.1",
      port = 0,
      HttpConnectionContext()
    ).futureValue

    // https://tools.ietf.org/html/rfc7540#section-3.2
    "respond with HTTP 101" in {
      // Not the whole frame, but only the identifiers and values - so an empty string for 0 settings is valid:
      val settings = ""
      val upgradeRequest =
        s"""GET / HTTP/1.1
Host: localhost
Upgrade: h2c
HTTP2-Settings: $settings

"""
      val sink = Source.single(ByteString(upgradeRequest)).concat(Source.maybe)
        .via(Tcp().outgoingConnection(binding.localAddress.getHostName, binding.localAddress.getPort))
        .runWith(Sink.queue())
      val response = sink.pull().futureValue.get.map(_.toChar).mkString
      response should include("HTTP/1.1 101 Switching Protocols")
      response should include("Upgrade: h2c")
      response should include("Connection: upgrade")
      val responseBody = sink.pull().futureValue.get

      val reader = new ByteReader(responseBody)
      val length = reader.readShortBE() << 8 | reader.readByte()

      val tpe = Http2Protocol.FrameType.byId(reader.readByte())
      tpe should be(Http2Protocol.FrameType.SETTINGS)

      val flags = new ByteFlag(reader.readByte())
      val ack = Flags.ACK.isSet(flags)
      ack should be(false)

      val streamId = reader.readIntBE()
      Http2Compliance.requireZeroStreamId(streamId)

      val payload = new ByteReader(reader.take(length))
      if (payload.remainingSize % 6 != 0) throw new Http2Compliance.IllegalPayloadLengthInSettingsFrame(payload.remainingSize, "SETTINGS payload MUST be a multiple of multiple of 6 octets")
      SettingsFrame(readSettings(payload))
    }
  }
}
