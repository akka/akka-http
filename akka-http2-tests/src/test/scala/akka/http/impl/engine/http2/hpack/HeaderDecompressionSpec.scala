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
import akka.util.ByteString

class HeaderDecompressionSpec extends AkkaSpecWithMaterializer {

  def pseudoHeaders(path: String): Seq[(String, String)] =
    Seq(":method" -> "GET", ":scheme" -> "http", ":path" -> path, ":authority" -> "www.example.com")

  /** Runs the blocks through a single decompression stage, as they would arrive on one connection */
  def decompress(settings: ServerSettings, blocks: ByteString*): Seq[ParsedHeadersFrame] = {
    val parserSettings = settings.parserSettings
    Source(blocks.zipWithIndex.map {
      case (block, idx) => HeadersFrame(1 + idx * 2, endStream = true, endHeaders = true, block, None)
    }.toList)
      .via(new HeaderDecompression(HttpHeaderParser(parserSettings, log), parserSettings, settings.http2Settings))
      .runWith(Sink.seq).futureValue
      .map(_.asInstanceOf[ParsedHeadersFrame])
  }

  "HeaderDecompression" should {

    "keep the HPACK dynamic table in sync when a header block is rejected" in {
      val encoder = new HPackEncodingSupport {}

      // 'connection' is not allowed in HTTP/2 and is rejected, but the headers after it still enter the
      // peer's dynamic table. If decoding stops at the rejected header the tables drift apart, and every
      // later block on the connection resolves its indices against the wrong entries.
      val rejected = encoder.encodeHeaderPairs(
        pseudoHeaders("/one") ++ Seq("connection" -> "close", "x-marker" -> "hello"))
      // encoded almost entirely as references into the dynamic table built up by the block above
      val accepted = encoder.encodeHeaderPairs(pseudoHeaders("/one") :+ ("x-marker" -> "hello"))

      val Seq(first, second) = decompress(ServerSettings(system), rejected, accepted)

      first.headerParseErrorDetails.map(_.summary) shouldBe
        Some("Malformed request: Header 'Connection' must not be used with HTTP/2")

      second.headerParseErrorDetails shouldBe empty
      second.keyValuePairs.collectFirst { case (":path", (path, _)) => path.toString } shouldBe Some("/one")
      second.keyValuePairs.collectFirst { case ("x-marker", v) => v.toString } shouldBe Some("x-marker: hello")
    }

    "not carry the decoded header list size of a rejected header block over to the next block" in {
      val settings = ServerSettings(system).mapHttp2Settings(_.withMaxHeaderListSize(200))
      val encoder = new HPackEncodingSupport {}

      // 'connection' is not allowed in HTTP/2 so decoding is aborted when it is reached, after roughly 150
      // bytes of header list have already been counted. It is encoded as 'never indexed' so that the abort
      // does not desync the HPACK dynamic table as well.
      val rejected =
        encoder.encodeHeaderPairs(pseudoHeaders("/one") :+ ("x-pad" -> "a" * 100)) ++
          encoder.encodeNeverIndexedHeader("connection", "close")
      val accepted = encoder.encodeHeaderPairs(pseudoHeaders("/two"))

      val Seq(first, second) = decompress(settings, rejected, accepted)

      first.headerParseErrorDetails.map(_.summary) shouldBe Some("Malformed request: Header 'Connection' must not be used with HTTP/2")
      second.headerParseErrorDetails shouldBe empty
      second.keyValuePairs.collectFirst { case (":path", (path, _)) => path.toString } shouldBe Some("/two")
    }
  }
}
