/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.model.parser

import akka.annotation.InternalApi
import akka.http.scaladsl.settings.ParserSettings
import akka.http.scaladsl.settings.ParserSettings.CookieParsingMode
import akka.http.scaladsl.settings.ParserSettings.IllegalResponseHeaderValueProcessingMode
import akka.http.scaladsl.model.headers.HttpCookiePair
import akka.stream.impl.ConstantFun

import scala.util.control.NonFatal
import akka.http.impl.util.SingletonException
import akka.parboiled2._
import akka.shapeless._
import akka.http.scaladsl.model._

/**
 * INTERNAL API.
 */
@InternalApi
private[http] class HeaderParser(
  val input: ParserInput,
  settings:  HeaderParser.Settings = HeaderParser.DefaultSettings)
  extends Parser with DynamicRuleHandler[HeaderParser, HttpHeader :: HNil]
  with CommonRules
  with AcceptCharsetHeader
  with AcceptEncodingHeader
  with AcceptHeader
  with AcceptLanguageHeader
  with CacheControlHeader
  with ContentDispositionHeader
  with ContentTypeHeader
  with CommonActions
  with IpAddressParsing
  with LinkHeader
  with SimpleHeaders
  with StringBuilding
  with WebSocketHeaders {
  import CharacterClasses._

  override def customMediaTypes = settings.customMediaTypes

  // http://www.rfc-editor.org/errata_search.php?rfc=7230 errata id 4189
  def `header-field-value`: Rule1[String] = rule {
    FWS ~ clearSB() ~ `field-value` ~ FWS ~ EOI ~ push(sb.toString)
  }
  def `field-value` = {
    var fwsStart = cursor
    rule {
      zeroOrMore(`field-value-chunk`).separatedBy { // zeroOrMore because we need to also accept empty values
        run { fwsStart = cursor } ~ FWS ~ &(`field-value-char`) ~ run { if (cursor > fwsStart) sb.append(' ') }
      }
    }
  }
  def `field-value-chunk` = rule { oneOrMore(`field-value-char` ~ appendSB()) }
  def `field-value-char` = rule { VCHAR | `obs-text` }
  def FWS = rule { zeroOrMore(WSP) ~ zeroOrMore(`obs-fold`) }
  def `obs-fold` = rule { CRLF ~ oneOrMore(WSP) }

  ///////////////// DynamicRuleHandler //////////////

  override type Result = HeaderParser.Result
  def parser: HeaderParser = this
  def success(result: HttpHeader :: HNil): Result = HeaderParser.Success(result.head)
  def parseError(error: ParseError): HeaderParser.Failure = {
    val formatter = new ErrorFormatter(showLine = false)
    HeaderParser.Failure(ErrorInfo(formatter.format(error, input), formatter.formatErrorLine(error, input)))
  }
  def failure(error: Throwable): HeaderParser.Failure =
    HeaderParser.Failure {
      error match {
        case IllegalUriException(info) ⇒ info
        case NonFatal(e)               ⇒ ErrorInfo.fromCompoundString(e.getMessage)
      }
    }
  def ruleNotFound(ruleName: String): Result = HeaderParser.RuleNotFound

  def newUriParser(input: ParserInput): UriParser = new UriParser(input, uriParsingMode = settings.uriParsingMode)

  def `cookie-value`: Rule1[String] =
    settings.cookieParsingMode match {
      case CookieParsingMode.RFC6265 ⇒ rule { `cookie-value-rfc-6265` }
      case CookieParsingMode.Raw     ⇒ rule { `cookie-value-raw` }
    }

  def createCookiePair(name: String, value: String): HttpCookiePair = settings.cookieParsingMode match {
    case CookieParsingMode.RFC6265 ⇒ HttpCookiePair(name, value)
    case CookieParsingMode.Raw     ⇒ HttpCookiePair.raw(name, value)
  }
}

/**
 * INTERNAL API.
 */
@InternalApi
private[http] object HeaderParser {
  sealed trait Result
  case class Success(header: HttpHeader) extends Result
  case class Failure(info: ErrorInfo) extends Result
  case object RuleNotFound extends Result

  object EmptyCookieException extends SingletonException("Cookie header contained no parsable cookie values.")

  def lookupParser(headerName: String, settings: Settings = DefaultSettings): Option[String ⇒ HeaderParser#Result] =
    dispatch.lookup(headerName).map { runner ⇒ (value: String) ⇒
      import akka.parboiled2.EOI
      val v = value + EOI // this makes sure the parser isn't broken even if there's no trailing garbage in this value
      val parser = new HeaderParser(v, settings)
      runner(parser) match {
        case r @ Success(_) if parser.cursor == v.length ⇒ r
        case r @ Success(_) ⇒
          Failure(ErrorInfo(
            "Header parsing error",
            s"Rule for $headerName accepted trailing garbage. Is the parser missing a trailing EOI?"))
        case Failure(e)   ⇒ Failure(e.copy(summary = e.summary.filterNot(_ == EOI), detail = e.detail.filterNot(_ == EOI)))
        case RuleNotFound ⇒ RuleNotFound
      }
    }

  def parseFull(headerName: String, value: String, settings: Settings = DefaultSettings): HeaderParser#Result =
    lookupParser(headerName, settings).map(_(value)).getOrElse(HeaderParser.RuleNotFound)

  val (dispatch, ruleNames) = DynamicRuleDispatch[HeaderParser, HttpHeader :: HNil](
    "accept",
    "accept-charset",
    "accept-encoding",
    "accept-language",
    "accept-ranges",
    "access-control-allow-credentials",
    "access-control-allow-headers",
    "access-control-allow-methods",
    "access-control-allow-origin",
    "access-control-expose-headers",
    "access-control-max-age",
    "access-control-request-headers",
    "access-control-request-method",
    "accept",
    "age",
    "allow",
    "authorization",
    "cache-control",
    "connection",
    "content-disposition",
    "content-encoding",
    "content-length",
    "content-range",
    "content-type",
    "cookie",
    "date",
    "etag",
    "expect",
    "expires",
    "host",
    "if-match",
    "if-modified-since",
    "if-none-match",
    "if-range",
    "if-unmodified-since",
    "last-modified",
    "link",
    "location",
    "origin",
    "proxy-authenticate",
    "proxy-authorization",
    "range",
    "referer",
    "retry-after",
    "server",
    "sec-websocket-accept",
    "sec-websocket-extensions",
    "sec-websocket-key",
    "sec-websocket-protocol",
    "sec-websocket-version",
    "set-cookie",
    "strict-transport-security",
    "transfer-encoding",
    "upgrade",
    "user-agent",
    "www-authenticate",
    "x-forwarded-for",
    "x-forwarded-host",
    "x-forwarded-proto",
    "x-real-ip")

  abstract class Settings {
    def uriParsingMode: Uri.ParsingMode
    def cookieParsingMode: ParserSettings.CookieParsingMode
    def customMediaTypes: MediaTypes.FindCustom
    def illegalResponseHeaderValueProcessingMode: IllegalResponseHeaderValueProcessingMode
  }
  def Settings(
    uriParsingMode:    Uri.ParsingMode                          = Uri.ParsingMode.Relaxed,
    cookieParsingMode: ParserSettings.CookieParsingMode         = ParserSettings.CookieParsingMode.RFC6265,
    customMediaTypes:  MediaTypes.FindCustom                    = ConstantFun.scalaAnyTwoToNone,
    mode:              IllegalResponseHeaderValueProcessingMode = ParserSettings.IllegalResponseHeaderValueProcessingMode.Error): Settings = {

    val _uriParsingMode = uriParsingMode
    val _cookieParsingMode = cookieParsingMode
    val _customMediaTypes = customMediaTypes
    val _illegalResponseHeaderValueProcessingMode = mode

    new Settings {
      def uriParsingMode: Uri.ParsingMode = _uriParsingMode
      def cookieParsingMode: CookieParsingMode = _cookieParsingMode
      def customMediaTypes: MediaTypes.FindCustom = _customMediaTypes
      def illegalResponseHeaderValueProcessingMode: IllegalResponseHeaderValueProcessingMode =
        _illegalResponseHeaderValueProcessingMode
    }
  }
  val DefaultSettings: Settings = Settings()
}
