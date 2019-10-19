/**
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.model.http2

import akka.annotation.InternalApi
import akka.http.scaladsl.model.headers.{ CustomHeader, ModeledCustomHeader, ModeledCustomHeaderCompanion, RequestHeader }
import akka.http.impl.engine.http2.framing.Http2FrameParsing
import akka.http.impl.engine.http2.FrameEvent.Setting
import akka.http.impl.model.parser.Base64Parsing
import akka.stream.impl.io
import akka.stream.impl.io.ByteStringParser
import akka.util.ByteString

import scala.collection.immutable
import scala.util.Try

/**
 * Internal API
 */
@InternalApi
private[akka] object Http2SettingsHeader {
  val name: String = "http2-settings"

  def headerValueToBinary(value: String): ByteString =
    ByteString(Base64Parsing.base64UrlStringDecoder(value.toCharArray))

  def parse(value: String): Try[immutable.Seq[Setting]] = Try {
    // settings are a base64url encoded Http2 settings frame
    // https://httpwg.org/specs/rfc7540.html#rfc.section.3.2.1
    val bytes = headerValueToBinary(value)
    val reader = new io.ByteStringParser.ByteReader(bytes)
    Http2FrameParsing.readSettings(reader)
  }
}
