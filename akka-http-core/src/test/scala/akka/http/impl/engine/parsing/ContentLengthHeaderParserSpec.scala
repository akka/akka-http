/*
 * Copyright (C) 2009-2025 Lightbend Inc. <https://akka.io>
 */

package akka.http.impl.engine.parsing

import akka.util.ByteString
import akka.http.scaladsl.model.headers.`Content-Length`
import akka.http.impl.engine.parsing.SpecializedHeaderValueParsers.ContentLengthParser
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

abstract class ContentLengthHeaderParserSpec(mode: String, newLine: String) extends AnyWordSpec with Matchers {

  s"specialized ContentLength parser (mode: $mode)" should {
    "accept zero" in {
      parse("0") shouldEqual 0L
    }
    "accept positive value" in {
      parse("43234398") shouldEqual 43234398L
    }
    "accept positive value > Int.MaxValue <= Long.MaxValue" in {
      parse("274877906944") shouldEqual 274877906944L
      parse("9223372036854775807") shouldEqual 9223372036854775807L // Long.MaxValue
    }
    "don't accept positive value > Long.MaxValue" in {
      a[ParsingException] should be thrownBy parse("9223372036854775808") // Long.MaxValue + 1
      a[ParsingException] should be thrownBy parse("92233720368547758070") // Long.MaxValue * 10 which is 0 taken overflow into account
      a[ParsingException] should be thrownBy parse("92233720368547758080") // (Long.MaxValue + 1) * 10 which is 0 taken overflow into account
    }
    "don't accept values wrapping a full 64-bit range" in {
      a[ParsingException] should be thrownBy parse("18446744073709551616") // 2^64, wraps to 0
      a[ParsingException] should be thrownBy parse("18446744073709551620") // 2^64 + 4, wraps to 4
      a[ParsingException] should be thrownBy parse("18446744073709551700") // 2^64 + 84, wraps to 84
      a[ParsingException] should be thrownBy parse("1844674407370955161642") // 100 * 2^64 + 42, wraps to 42
    }
  }

  def parse(bigint: String): Long = {
    val (`Content-Length`(length), _) = ContentLengthParser(null, ByteString(bigint + newLine).compact, 0, _ => ())
    length
  }

}

class ContentLengthHeaderParserCRLFSpec extends ContentLengthHeaderParserSpec("CRLF", "\r\n")

class ContentLengthHeaderParserLFSpec extends ContentLengthHeaderParserSpec("LF", "\n")
