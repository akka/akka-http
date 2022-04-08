/*
 * Copyright (C) 2009-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.model.parser

import akka.http.scaladsl.settings.ParserSettings.CookieParsingMode
import akka.http.impl.model.parser.HeaderParser.Settings
import org.scalatest.matchers.{ MatchResult, Matcher }
import akka.http.impl.util._
import akka.http.scaladsl.model.{ HttpHeader, _ }
import headers._
import CacheDirectives._
import MediaTypes._
import MediaRanges._
import HttpCharsets._
import HttpEncodings._
import HttpMethods._

import java.net.InetAddress
import akka.http.scaladsl.model.MediaType.WithOpenCharset
import org.scalatest.exceptions.TestFailedException
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class HttpHeaderAuxSpec extends AnyFreeSpec with Matchers {
  val `application/vnd.spray` = MediaType.applicationBinary("vnd.spray", MediaType.Compressible)
  val PROPFIND = HttpMethod.custom("PROPFIND")

  "The HTTP header model must correctly parse and render the headers" - {

    "If-Match dispatching" in {
      // https://github.com/akka/akka-http/issues/443 Check dispatching for "if-match" does not throw "RuleNotFound"
      import scala.util._
      val Failure(cause) = Try(HeaderParser.dispatch(null, "if-match"))
      cause.getClass should be(classOf[NullPointerException])
    }

  }
}
