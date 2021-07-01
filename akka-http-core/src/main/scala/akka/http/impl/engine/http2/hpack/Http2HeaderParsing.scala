package akka.http.impl.engine.http2.hpack

import akka.annotation.InternalApi
import akka.http.impl.engine.http2.RequestParsing.malformedRequest
import akka.http.scaladsl.model
import akka.http.scaladsl.model.HttpHeader.ParsingResult
import model.{ HttpHeader, HttpMethod, HttpMethods, IllegalUriException, ParsingException, StatusCode, Uri }
import akka.http.scaladsl.settings.ParserSettings

@InternalApi
private[akka] object Http2HeaderParsing {
  sealed abstract class HeaderParser[+T](val headerName: String) {
    def parse(name: String, value: String, parserSettings: ParserSettings): T
    def get(value: AnyRef): T = value.asInstanceOf[T]
  }
  sealed abstract class Verbatim(headerName: String) extends HeaderParser[String](headerName) {
    override def parse(name: String, value: String, parserSettings: ParserSettings): String = value
  }

  object Scheme extends Verbatim(":scheme")
  object Method extends HeaderParser[HttpMethod](":method") {
    override def parse(name: String, value: String, parserSettings: ParserSettings): HttpMethod =
      HttpMethods.getForKey(value)
        .orElse(parserSettings.customMethods(value))
        .getOrElse(malformedRequest(s"Unknown HTTP method: '$value'"))
  }
  object PathAndQuery extends HeaderParser[(Uri.Path, Option[String])](":path") {
    override def parse(name: String, value: String, parserSettings: ParserSettings): (Uri.Path, Option[String]) =
      try {
        Uri.parseHttp2PathPseudoHeader(value, mode = parserSettings.uriParsingMode)
      } catch {
        case IllegalUriException(info) => throw new ParsingException(info)
      }
  }
  object Authority extends HeaderParser[Uri.Authority](":authority") {
    override def parse(name: String, value: String, parserSettings: ParserSettings): Uri.Authority =
      try {
        Uri.parseHttp2AuthorityPseudoHeader(value /*FIXME: , mode = serverSettings.parserSettings.uriParsingMode*/ )
      } catch {
        case IllegalUriException(info) => throw new ParsingException(info)
      }
  }
  object Status extends HeaderParser[StatusCode](":status") {
    override def parse(name: String, value: String, parserSettings: ParserSettings): StatusCode =
      value.toInt
  }
  object ContentType extends HeaderParser[model.ContentType]("content-type") {
    override def parse(name: String, value: String, parserSettings: ParserSettings): model.ContentType =
      model.ContentType.parse(value).right.getOrElse(malformedRequest(s"Invalid content-type: '$value'"))
  }
  object ContentLength extends Verbatim("content-length")
  object Cookie extends Verbatim("cookie")
  object OtherHeader extends HeaderParser[HttpHeader]("<other>") {
    override def parse(name: String, value: String, parserSettings: ParserSettings): HttpHeader =
      throw new IllegalStateException("Needs to be parsed directly")
  }

  val Parsers: Map[String, HeaderParser[AnyRef]] =
    Seq(
      Method, Scheme, Authority, PathAndQuery, ContentType, Status, ContentLength, Cookie
    ).map(p => p.headerName -> p).toMap

  def parse(name: String, value: String, parserSettings: ParserSettings): (String, AnyRef) = {
    name -> Parsers.getOrElse(name, Modeled).parse(name, value, parserSettings)
  }

  private object Modeled extends HeaderParser[HttpHeader]("<modeled>") {
    override def parse(name: String, value: String, parserSettings: ParserSettings): HttpHeader =
      HttpHeader.parse(name, value, parserSettings) match {
        case ParsingResult.Ok(header, _) => header
        case ParsingResult.Error(error)  => throw new IllegalStateException(error.detail)
      }
  }
}
