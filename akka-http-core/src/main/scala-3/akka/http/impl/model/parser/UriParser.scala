/*
 * Copyright (C) 2009-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.model.parser

import java.nio.charset.Charset

import akka.parboiled2._
import akka.http.impl.util.{ StringRendering, enhanceString_ }
import akka.http.scaladsl.model.{ Uri, UriRendering }
import akka.http.scaladsl.model.headers.HttpOrigin
import Parser.DeliveryScheme.Either
import Uri._
import akka.annotation.InternalApi

/**
 * INTERNAL API
 *
 * http://tools.ietf.org/html/rfc3986
 */
@InternalApi
private[http] final class UriParser(
  private[this] var _input: ParserInput,
  val uriParsingCharset:    Charset,
  val uriParsingMode:       Uri.ParsingMode,
  val maxValueStackSize:    Int) extends Parser(maxValueStackSize = maxValueStackSize) /*with IpAddressParsing with StringBuilding*/ {
  import CharacterClasses._

  override def input: ParserInput = _input

  def this(
    input:             ParserInput,
    uriParsingCharset: Charset         = UTF8,
    uriParsingMode:    Uri.ParsingMode = Uri.ParsingMode.Relaxed) =
    this(input, uriParsingCharset, uriParsingMode, 1024)

  def parseAbsoluteUri(): Uri = ???

  def parseUriReference(): Uri = ???

  def parseAndResolveUriReference(base: Uri): Uri = ???

  def parseOrigin(): HttpOrigin = ???

  def parseHost(): Host = ???

  /**
   * @return a 'raw' (percent-encoded) query string that does not contain invalid characters.
   */
  def parseRawQueryString(): String = ???

  /**
   * @param rawQueryString 'raw' (percent-encoded) query string that in Relaxed mode may contain characters not allowed
   * by https://tools.ietf.org/html/rfc3986#section-3.4 but is guaranteed not to have invalid percent-encoded characters
   * @return a 'raw' (percent-encoded) query string that does not contain invalid characters.
   */
  def parseSafeRawQueryString(rawQueryString: String): String = uriParsingMode match {
    case Uri.ParsingMode.Strict =>
      // Cannot contain invalid characters in strict mode
      rawQueryString
    case Uri.ParsingMode.Relaxed =>
      // Percent-encode invalid characters
      UriRendering.encode(new StringRendering, rawQueryString, uriParsingCharset, `query-fragment-char` ++ '%', false).get
  }

  def parseQuery(): Query = ???

  def parseAuthority(): Authority = ???
  /*rule(authority ~ EOI).run() match {
      case Right(_)    => Authority(_host, _port, _userinfo)
      case Left(error) => fail(error, "authority")
    }*/

  //  def fail(error: ParseError, target: String): Nothing = {
  //    val formatter = new ErrorFormatter(showLine = false)
  //    Uri.fail(s"Illegal $target: " + formatter.format(error, input), formatter.formatErrorLine(error, input))
  //  }
  //
  //  private[this] val `path-segment-char` = uriParsingMode match {
  //    case Uri.ParsingMode.Strict => `pchar-base`
  //    case _                      => `relaxed-path-segment-char`
  //  }
  //  private[this] val `query-char` = uriParsingMode match {
  //    case Uri.ParsingMode.Strict => `query-fragment-char`
  //    case _                      => `relaxed-query-char`
  //  }
  //  private[this] val `query-key-char` = uriParsingMode match {
  //    case Uri.ParsingMode.Strict  => `strict-query-key-char`
  //    case Uri.ParsingMode.Relaxed => `relaxed-query-key-char`
  //  }
  //  private[this] val `query-value-char` = uriParsingMode match {
  //    case Uri.ParsingMode.Strict  => `strict-query-value-char`
  //    case Uri.ParsingMode.Relaxed => `relaxed-query-value-char`
  //  }
  //  private[this] val `fragment-char` = uriParsingMode match {
  //    case Uri.ParsingMode.Strict => `query-fragment-char`
  //    case _                      => `relaxed-fragment-char`
  //  }
  //
  //  // New vars need to be reset in `reset` below
  //  private[this] var _scheme = ""
  //  private[this] var _userinfo = ""
  //  private[this] var _host: Host = Host.Empty
  //  private[this] var _port: Int = 0
  //  private[this] var _path: Path = Path.Empty
  //  /**
  //   *  Percent-encoded. When in in 'relaxed' mode, characters not permitted by https://tools.ietf.org/html/rfc3986#section-3.4
  //   *  are already automatically percent-encoded here
  //   */
  //  private[this] var _rawQueryString: Option[String] = None
  //  private[this] var _fragment: Option[String] = None
  //
  //  /** Allows to reuse this parser. */
  //  def reset(newInput: ParserInput): Unit = {
  //    _input = newInput
  //    _scheme = ""
  //    _userinfo = ""
  //    _host = Host.Empty
  //    _port = 0
  //    _path = Path.Empty
  //    _rawQueryString = None
  //    _fragment = None
  //    _firstPercentIx = -1
  //  }
  //
  //  private[this] def setScheme(scheme: String): Unit = _scheme = scheme
  //  private[this] def setUserInfo(userinfo: String): Unit = _userinfo = userinfo
  //  private[this] def setHost(host: Host): Unit = _host = host
  //  private[this] def setPort(port: Int): Unit = _port = port
  //  private[this] def setPath(path: Path): Unit = _path = path
  //  private[this] def setRawQueryString(rawQueryString: String): Unit = _rawQueryString = Some(parseSafeRawQueryString(rawQueryString))
  //  private[this] def setFragment(fragment: String): Unit = _fragment = Some(fragment)
  //
  //  // http://tools.ietf.org/html/rfc3986#appendix-A
  //
  //  def URI = rule { scheme ~ ':' ~ `hier-part` ~ optional('?' ~ rawQueryString) ~ optional('#' ~ fragment) }
  //
  //  def origin = rule { scheme ~ ':' ~ '/' ~ '/' ~ hostAndPort }
  //
  //  def `hier-part` = rule(
  //    '/' ~ '/' ~ authority ~ `path-abempty`
  //      | `path-absolute`
  //      | `path-rootless`
  //      | `path-empty`)
  //
  //  def `URI-reference` = rule { URI | `relative-ref` }
  //
  def `URI-reference-pushed`: Rule1[Uri] = ??? // rule { `URI-reference` ~ push(createUriReference()) }
  //
  //  def `absolute-URI` = rule { scheme ~ ':' ~ `hier-part` ~ optional('?' ~ rawQueryString) }
  //
  //  def `relative-ref` = rule { `relative-part` ~ optional('?' ~ rawQueryString) ~ optional('#' ~ fragment) }
  //
  //  def `relative-part` = rule(
  //    '/' ~ '/' ~ authority ~ `path-abempty`
  //      | `path-absolute`
  //      | `path-noscheme`
  //      | `path-empty`)
  //
  //  def scheme = rule(
  //    'h' ~ 't' ~ 't' ~ 'p' ~ (&(':') ~ run(setScheme("http")) | 's' ~ &(':') ~ run(setScheme("https")))
  //      | clearSB() ~ ALPHA ~ appendLowered() ~ zeroOrMore(`scheme-char` ~ appendLowered()) ~ &(':') ~ run(setScheme(sb.toString)))
  //
  //  def `scheme-pushed` = rule { oneOrMore(`scheme-char` ~ appendLowered()) ~ run(setScheme(sb.toString)) ~ push(_scheme) }
  //
  //  def authority = rule { optional(userinfo) ~ hostAndPort }
  //
  //  def userinfo = rule {
  //    clearSBForDecoding() ~ zeroOrMore(`userinfo-char` ~ appendSB() | `pct-encoded`) ~ '@' ~ run(setUserInfo(getDecodedString()))
  //  }
  //
  //  def hostAndPort = rule { host ~ optional(':' ~ port) }
  //
  //  def `hostAndPort-pushed` = rule { hostAndPort ~ push(_host) ~ push(_port) }
  //
  //  def host = rule { `IP-literal` | ipv4Host | `reg-name` }
  //
  //  /** A relaxed host rule to use in `parseHost` that also recognizes IPv6 address without the brackets. */
  //  def relaxedHost = rule { `IP-literal` | ipv6Host | ipv4Host | `reg-name` }
  //
  //  def port = rule {
  //    DIGIT ~ run(setPort(lastChar - '0')) ~ optional(
  //      DIGIT ~ run(setPort(10 * _port + lastChar - '0')) ~ optional(
  //        DIGIT ~ run(setPort(10 * _port + lastChar - '0')) ~ optional(
  //          DIGIT ~ run(setPort(10 * _port + lastChar - '0')) ~ optional(
  //            DIGIT ~ run(setPort(10 * _port + lastChar - '0'))))))
  //  }
  //
  //  def `IP-literal` = rule { '[' ~ ipv6Host ~ ']' } // IPvFuture not currently recognized
  //
  //  def ipv4Host = rule { capture(`ip-v4-address`) ~ &(colonSlashEOI) ~> ((b, a) => _host = IPv4Host(b, a)) }
  //  def ipv6Host = rule { capture(`ip-v6-address`) ~> ((b, a) => setHost(IPv6Host(b, a))) }
  //
  //  def `reg-name` = rule(
  //    clearSBForDecoding() ~ oneOrMore(`lower-reg-name-char` ~ appendSB() | UPPER_ALPHA ~ appendLowered() | `pct-encoded`) ~
  //      run(setHost(NamedHost(getDecodedStringAndLowerIfEncoded(UTF8))))
  //      | run(setHost(Host.Empty)))
  //
  //  def `path-abempty` = rule { clearSB() ~ slashSegments ~ savePath() }
  //  def `path-absolute` = rule { clearSB() ~ '/' ~ appendSB('/') ~ optional(`segment-nz` ~ slashSegments) ~ savePath() }
  //  def `path-noscheme` = rule { clearSB() ~ `segment-nz-nc` ~ slashSegments ~ savePath() }
  //  def `path-rootless` = rule { clearSB() ~ `segment-nz` ~ slashSegments ~ savePath() }
  //  def `path-empty` = rule { MATCH }
  //
  //  def slashSegments = rule { zeroOrMore('/' ~ appendSB('/') ~ segment) }
  //
  //  def segment = rule { zeroOrMore(pchar) }
  //  def `segment-nz` = rule { oneOrMore(pchar) }
  //  def `segment-nz-nc` = rule { oneOrMore(!':' ~ pchar) }
  //
  //  def pchar = rule { `path-segment-char` ~ appendSB() | `pct-encoded` }
  //
  //  def rawQueryString = rule {
  //    clearSB() ~ oneOrMore(`query-char` ~ appendSB() | `pct-encoded`) ~ run(setRawQueryString(sb.toString)) | run(setRawQueryString(""))
  //  }

  // https://www.w3.org/TR/html401/interact/forms.html#h-17.13.4.1
  def query: Rule1[Query] = ???

  def fragment = ???

  def `pct-encoded` = ???

  //////////////////////////// ADDITIONAL HTTP-SPECIFIC RULES //////////////////////////

  // http://tools.ietf.org/html/rfc7230#section-2.7
  def `absolute-path` = ???

  // http://tools.ietf.org/html/rfc7230#section-5.3
  def `request-target` = ???

  def parseHttpRequestTarget(): Uri = ???

  /////////////////////////// ADDITIONAL HTTP/2-SPECIFIC RULES /////////////////////////

  // https://tools.ietf.org/html/rfc7540#section-8.1.2.3
  // https://tools.ietf.org/html/rfc3986#section-3.2 - without deprecated userinfo
  def `http2-authority-pseudo-header` = ???

  def parseHttp2AuthorityPseudoHeader(): Uri.Authority = ???

  // https://tools.ietf.org/html/rfc7540#section-8.1.2.3
  def `http2-path-pseudo-header` = ???

  /**
   * @return path and percent-encoded query string. When in in 'relaxed' mode, characters not permitted by https://tools.ietf.org/html/rfc3986#section-3.4
   *         are already automatically percent-encoded here
   */
  def parseHttp2PathPseudoHeader(): (Uri.Path, Option[String]) = ???

  /** Allows to reuse this parser. */
  def reset(newInput: ParserInput): Unit = ???
}
