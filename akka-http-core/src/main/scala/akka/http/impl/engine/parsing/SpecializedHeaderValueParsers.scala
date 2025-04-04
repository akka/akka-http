/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
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
        if (result < 0) fail("`Content-Length` header value must not exceed 63-bit integer range")
        else if (DIGIT(c)) recurse(ix + 1, result * 10 + c - '0')
        else if (WSP(c)) recurse(ix + 1, result)
        else if (c == '\r' && byteChar(input, ix + 1) == '\n') (`Content-Length`(result), ix + 2)
        else if (c == '\n') (`Content-Length`(result), ix + 1)
        else fail("Illegal `Content-Length` header value")
      }
      recurse()
    }
  }
}
