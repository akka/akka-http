package akka.http.impl.engine.http2.hpack

import akka.annotation.InternalApi
import akka.http.impl.engine.http2.RequestParsing.malformedRequest
import akka.http.scaladsl.model
import model.{ HttpHeader, HttpMethod, HttpMethods, IllegalUriException, ParsingException, Uri }
import akka.http.scaladsl.settings.ParserSettings

@InternalApi
private[akka] object Http2HeaderParsing {
  sealed trait HeaderParser[+T] {
    def parse(parserSettings: ParserSettings, value: String): T
    def get(value: AnyRef): T = value.asInstanceOf[T]
  }
  sealed abstract class Unparsed extends HeaderParser[String] {
    override def parse(parserSettings: ParserSettings, value: String): String = value
  }

  object Scheme extends Unparsed
  object Method extends HeaderParser[HttpMethod] {
    override def parse(parserSettings: ParserSettings, value: String): HttpMethod =
      HttpMethods.getForKey(value)
        .orElse(parserSettings.customMethods(value))
        .getOrElse(malformedRequest(s"Unknown HTTP method: '$value'"))
  }
  object PathAndQuery extends HeaderParser[(Uri.Path, Option[String])] {
    override def parse(parserSettings: ParserSettings, value: String): (Uri.Path, Option[String]) =
      try {
        Uri.parseHttp2PathPseudoHeader(value, mode = parserSettings.uriParsingMode)
      } catch {
        case IllegalUriException(info) => throw new ParsingException(info)
      }
  }
  object Authority extends HeaderParser[Uri.Authority] {
    override def parse(parserSettings: ParserSettings, value: String): Uri.Authority =
      try {
        Uri.parseHttp2AuthorityPseudoHeader(value /*FIXME: , mode = serverSettings.parserSettings.uriParsingMode*/ )
      } catch {
        case IllegalUriException(info) => throw new ParsingException(info)
      }
  }
  object ContentType extends HeaderParser[model.ContentType] {
    override def parse(parserSettings: ParserSettings, value: String): model.ContentType =
      model.ContentType.parse(value).right.getOrElse(malformedRequest(s"Invalid content-type: '$value'"))
  }
  object ContentLength extends Unparsed
  object Cookie extends Unparsed
  object OtherHeader extends HeaderParser[HttpHeader] {
    override def parse(parserSettings: ParserSettings, value: String): HttpHeader =
      throw new IllegalStateException("Needs to be parsed directly")
  }
}
