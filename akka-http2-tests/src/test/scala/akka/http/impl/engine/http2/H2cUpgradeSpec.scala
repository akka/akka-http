/*
 * Copyright (C) 2019-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.http.impl.engine.http2.Http2Protocol.SettingIdentifier
import akka.http.impl.engine.http2.Http2Protocol.SettingIdentifier._
import akka.http.impl.util._
import akka.http.scaladsl.model.{ HttpResponse, StatusCodes }
import akka.http.scaladsl.HttpConnectionContext
import akka.stream.scaladsl.{ Source, Tcp }
import akka.util.ByteString

import scala.annotation.tailrec
import scala.concurrent.Future
import scala.concurrent.duration._

class H2cUpgradeSpec extends AkkaSpecWithMaterializer("""
    akka.http.server.enable-http2 = on
    akka.http.server.http2.log-frames = on
  """) {

  override implicit val patience = PatienceConfig(5.seconds, 5.seconds)

  "An HTTP/1.1 server without TLS that allows upgrading to cleartext HTTP/2" should {
    val binding = Http2().bindAndHandleAsync(
      _ => Future.successful(HttpResponse(status = StatusCodes.ImATeapot)),
      "127.0.0.1",
      port = 0,
      HttpConnectionContext()
    ).futureValue

    // https://tools.ietf.org/html/rfc7540#section-3.2
    "respond with HTTP 101 and no initial settings" in {
      val settings = encode(Nil)
      // Settings are placed directly in the HTTP header, without any framing,
      // so 'no settings' just yields the empty string:
      settings shouldBe ""
      testWith(settings)
    }

    "respond with HTTP 101 and some initial settings" in {
      // real-world settings example as used by curl
      val settings = encode(Seq(
        (SETTINGS_MAX_CONCURRENT_STREAMS, 100),
        (SETTINGS_INITIAL_WINDOW_SIZE, 33554432),
        (SETTINGS_ENABLE_PUSH, 0)
      ))
      settings shouldBe "AAMAAABkAAQCAAAAAAIAAAAA"
      testWith(settings)
    }

    def testWith(settings: String): Unit = {
      val upgradeRequest =
        s"""GET / HTTP/1.1
Host: localhost
Upgrade: h2c
HTTP2-Settings: $settings

"""
      val frameProbe = Http2FrameProbe()

      Source.single(ByteString(upgradeRequest)).concat(Source.maybe)
        .via(Tcp(system).outgoingConnection(binding.localAddress.getHostName, binding.localAddress.getPort))
        .runWith(frameProbe.sink)

      @tailrec def readToEndOfHeader(currentlyRead: String = ""): String =
        if (currentlyRead.endsWith("\r\n\r\n")) currentlyRead
        else readToEndOfHeader(currentlyRead + frameProbe.plainDataProbe.expectBytes(1).utf8String)

      val headers = readToEndOfHeader()

      headers should include("HTTP/1.1 101 Switching Protocols")
      headers should include("Upgrade: h2c")
      headers should include("Connection: upgrade")

      frameProbe.expectFrameFlagsStreamIdAndPayload(Http2Protocol.FrameType.SETTINGS)
      frameProbe.expectHeaderBlock(1, true)
    }
  }

  def encode(settings: Seq[(SettingIdentifier, Int)]): String = {
    val bytes = settings.flatMap {
      case (id, value) => Seq(
        0.toByte,
        id.id.toByte,
        (value >> 24).toByte,
        (value >> 16).toByte,
        (value >> 8).toByte,
        value.toByte)
    }
    ByteString.fromArrayUnsafe(bytes.toArray).encodeBase64.utf8String
  }
}
