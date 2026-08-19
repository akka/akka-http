/*
 * Copyright (C) 2009-2025 Lightbend Inc. <https://akka.io>
 */

package akka.http.impl.engine.parsing

import akka.annotation.InternalApi

import scala.annotation.tailrec
import akka.util.ByteString
import akka.http.impl.model.parser.CharacterClasses._
import akka.http.scaladsl.model.{ ErrorInfo, HttpHeader }
import akka.http.scaladsl.model.headers.`Content-Length`

/**
 * INTERNAL API
 */
@InternalApi
private[parsing] object SpecializedHeaderValueParsers {
  import HttpHeaderParser._

  def specializedHeaderValueParsers = Seq(ContentLengthParser)

  object ContentLengthParser extends HeaderValueParser("Content-Length", maxValueCount = 1) {
    def apply(hhp: HttpHeaderParser, input: ByteString, valueStart: Int, onIllegalHeader: ErrorInfo => Unit): (HttpHeader, Int) = {
      @tailrec def recurse(ix: Int = valueStart, result: Long = 0): (HttpHeader, Int) = {
        val c = byteChar(input, ix)
        if (DIGIT(c)) {
          val digit = c - '0'
          // checked before accumulating, a negative result afterwards misses values wrapping the full 64-bit range
          // 7 is the last digit of Long.MaxValue
          if (result > Long.MaxValue / 10 || (result == Long.MaxValue / 10 && digit > 7))
            fail("`Content-Length` header value must not exceed 63-bit integer range")
          else recurse(ix + 1, result * 10 + digit)
        } else if (WSP(c)) recurse(ix + 1, result)
        else if (c == '\r' && byteChar(input, ix + 1) == '\n') (`Content-Length`(result), ix + 2)
        else if (c == '\n') (`Content-Length`(result), ix + 1)
        else fail("Illegal `Content-Length` header value")
      }
      recurse()
    }
  }
}
