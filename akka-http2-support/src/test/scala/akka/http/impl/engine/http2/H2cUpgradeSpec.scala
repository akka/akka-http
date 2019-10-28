/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.http.impl.util._
import akka.http.scaladsl.model.{ HttpResponse, StatusCodes }
import akka.http.scaladsl.{ Http2, HttpConnectionContext }
import akka.stream.scaladsl.{ Source, Tcp }
import akka.util.ByteString

import scala.annotation.tailrec
import scala.concurrent.Future
import scala.concurrent.duration._

class H2cUpgradeSpec extends AkkaSpecWithMaterializer("""
    akka.http.server.preview.enable-http2 = on
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
    "respond with HTTP 101" in {
      // Not the whole frame, but only the identifiers and values - so an empty string for 0 settings is valid:
      val settings = ""
      val upgradeRequest =
        s"""GET / HTTP/1.1
Host: localhost
Upgrade: h2c
HTTP2-Settings: $settings

"""
      val frameProbe = Http2FrameProbe()

      Source.single(ByteString(upgradeRequest)).concat(Source.maybe)
        .via(Tcp().outgoingConnection(binding.localAddress.getHostName, binding.localAddress.getPort))
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
}
