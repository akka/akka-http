/*
 * Copyright (C) 2025 Lightbend Inc. <https://akka.io>
 */

package akka.http.impl.engine.http2.hpack

import akka.http.impl.engine.http2.FrameEvent.{ HeadersFrame, ParsedHeadersFrame }
import akka.http.impl.engine.http2.HPackEncodingSupport
import akka.http.impl.engine.parsing.HttpHeaderParser
import akka.http.impl.util.AkkaSpecWithMaterializer
import akka.http.scaladsl.settings.ServerSettings
import akka.stream.scaladsl.{ Sink, Source }

class HeaderDecompressionSpec extends AkkaSpecWithMaterializer {

  "HeaderDecompression" should {

    "not carry the decoded header list size of a rejected header block over to the next block" in {
      val settings = ServerSettings(system).mapHttp2Settings(_.withMaxHeaderListSize(200))
      val parserSettings = settings.parserSettings
      val encoder = new HPackEncodingSupport {}

      def pseudoHeaders(path: String): Seq[(String, String)] =
        Seq(":method" -> "GET", ":scheme" -> "http", ":path" -> path, ":authority" -> "www.example.com")

      // 'connection' is not allowed in HTTP/2 so decoding is aborted when it is reached, after roughly 150
      // bytes of header list have already been counted. It is encoded as 'never indexed' so that the abort
      // does not desync the HPACK dynamic table as well.
      val rejected =
        encoder.encodeHeaderPairs(pseudoHeaders("/one") :+ ("x-pad" -> "a" * 100)) ++
          encoder.encodeNeverIndexedHeader("connection", "close")
      val accepted = encoder.encodeHeaderPairs(pseudoHeaders("/two"))

      val frames = Source(List(
        HeadersFrame(1, endStream = true, endHeaders = true, rejected, None),
        HeadersFrame(3, endStream = true, endHeaders = true, accepted, None)))
        .via(new HeaderDecompression(HttpHeaderParser(parserSettings, log), parserSettings, settings.http2Settings))
        .runWith(Sink.seq).futureValue

      val Seq(first: ParsedHeadersFrame, second: ParsedHeadersFrame) = frames

      first.headerParseErrorDetails.map(_.summary) shouldBe Some("Malformed request: Header 'Connection' must not be used with HTTP/2")
      second.headerParseErrorDetails shouldBe empty
      second.keyValuePairs.collectFirst { case (":path", (path, _)) => path.toString } shouldBe Some("/two")
    }
  }
}
