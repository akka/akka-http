/*
 * Copyright (C) 2009-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import javax.net.ssl.SSLSession
import akka.annotation.InternalApi
import akka.http.impl.engine.parsing.HttpHeaderParser
import akka.http.impl.engine.server.HttpAttributes
import akka.http.scaladsl.model
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{ `Remote-Address`, `Tls-Session-Info` }
import akka.http.scaladsl.settings.ServerSettings
import akka.stream.Attributes
import akka.util.ByteString
import akka.util.OptionVal
import com.github.ghik.silencer.silent

import scala.annotation.tailrec
import scala.collection.immutable.VectorBuilder

/**
 * INTERNAL API
 */
@InternalApi
private[http2] object RequestParsing {

  @silent("use remote-address-attribute instead")
  def parseRequest(httpHeaderParser: HttpHeaderParser, serverSettings: ServerSettings, streamAttributes: Attributes): Http2SubStream => HttpRequest = {

    val remoteAddressHeader: Option[`Remote-Address`] =
      if (serverSettings.remoteAddressHeader) {
        streamAttributes.get[HttpAttributes.RemoteAddress].map(remote => model.headers.`Remote-Address`(RemoteAddress(remote.address)))
        // in order to avoid searching all the time for the attribute, we need to guard it with the setting condition
      } else None // no need to emit the remote address header

    val remoteAddressAttribute: Option[RemoteAddress] =
      if (serverSettings.remoteAddressAttribute) {
        streamAttributes.get[HttpAttributes.RemoteAddress].map(remote => RemoteAddress(remote.address))
      } else None

    val tlsSessionInfoHeader: Option[`Tls-Session-Info`] =
      if (serverSettings.parserSettings.includeTlsSessionInfoHeader) {
        streamAttributes.get[HttpAttributes.TLSSessionInfo].map(sslSessionInfo =>
          model.headers.`Tls-Session-Info`(sslSessionInfo.session))
      } else None

    val sslSessionAttribute: Option[SSLSession] =
      if (serverSettings.parserSettings.includeSslSessionAttribute)
        streamAttributes.get[HttpAttributes.TLSSessionInfo].map(_.session)
      else
        None

    { subStream =>
      @tailrec
      def rec(
        remainingHeaders:  Seq[(String, String)],
        method:            HttpMethod                 = null,
        scheme:            String                     = null,
        authority:         Uri.Authority              = null,
        pathAndRawQuery:   (Uri.Path, Option[String]) = null,
        contentType:       OptionVal[ContentType]     = OptionVal.None,
        contentLength:     Long                       = -1,
        cookies:           StringBuilder              = null,
        seenRegularHeader: Boolean                    = false,
        headers:           VectorBuilder[HttpHeader]  = new VectorBuilder[HttpHeader]
      ): HttpRequest =
        if (remainingHeaders.isEmpty) {
          // https://httpwg.org/specs/rfc7540.html#rfc.section.8.1.2.3: these pseudo header fields are mandatory for a request
          checkRequiredPseudoHeader(":scheme", scheme)
          checkRequiredPseudoHeader(":method", method)
          checkRequiredPseudoHeader(":path", pathAndRawQuery)

          if (cookies != null) {
            // Compress 'cookie' headers if present
            headers += parseHeaderPair(httpHeaderParser, "cookie", cookies.toString)
          }
          if (remoteAddressHeader.isDefined) headers += remoteAddressHeader.get

          if (tlsSessionInfoHeader.isDefined) headers += tlsSessionInfoHeader.get

          val entity = subStream.createEntity(contentLength, contentType)

          val (path, rawQueryString) = pathAndRawQuery
          val authorityOrDefault: Uri.Authority = if (authority == null) Uri.Authority.Empty else authority
          val uri = Uri(scheme, authorityOrDefault, path, rawQueryString)
          val request = HttpRequest(
            method, uri, headers.result(), entity, HttpProtocols.`HTTP/2.0`
          ).addAttribute(Http2.streamId, subStream.streamId)
          val requestWithSession = sslSessionAttribute match {
            case Some(sslSession) => request.addAttribute(AttributeKeys.sslSession, SslSessionInfo(sslSession))
            case None             => request
          }
          remoteAddressAttribute match {
            case Some(remoteAddress) => requestWithSession.addAttribute(AttributeKeys.remoteAddress, remoteAddress)
            case None                => requestWithSession
          }
        } else remainingHeaders.head match {
          case (":scheme", value) =>
            checkUniquePseudoHeader(":scheme", scheme)
            checkNoRegularHeadersBeforePseudoHeader(":scheme", seenRegularHeader)
            rec(remainingHeaders.tail, method, value, authority, pathAndRawQuery, contentType, contentLength, cookies, seenRegularHeader, headers)
          case (":method", value) =>
            checkUniquePseudoHeader(":method", method)
            checkNoRegularHeadersBeforePseudoHeader(":method", seenRegularHeader)
            val m = HttpMethods.getForKey(value)
              .orElse(serverSettings.parserSettings.customMethods(value))
              .getOrElse(malformedRequest(s"Unknown HTTP method: '$value'"))
            rec(remainingHeaders.tail, m, scheme, authority, pathAndRawQuery, contentType, contentLength, cookies, seenRegularHeader, headers)
          case (":path", value) =>
            checkUniquePseudoHeader(":path", pathAndRawQuery)
            checkNoRegularHeadersBeforePseudoHeader(":path", seenRegularHeader)
            val newPathAndRawQuery: (Uri.Path, Option[String]) = try {
              Uri.parseHttp2PathPseudoHeader(value, mode = serverSettings.parserSettings.uriParsingMode)
            } catch {
              case IllegalUriException(info) => throw new ParsingException(info)
            }
            rec(remainingHeaders.tail, method, scheme, authority, newPathAndRawQuery, contentType, contentLength, cookies, seenRegularHeader, headers)
          case (":authority", value) =>
            checkUniquePseudoHeader(":authority", authority)
            checkNoRegularHeadersBeforePseudoHeader(":authority", seenRegularHeader)
            val newAuthority: Uri.Authority = try {
              Uri.parseHttp2AuthorityPseudoHeader(value, mode = serverSettings.parserSettings.uriParsingMode)
            } catch {
              case IllegalUriException(info) => throw new ParsingException(info)
            }
            rec(remainingHeaders.tail, method, scheme, newAuthority, pathAndRawQuery, contentType, contentLength, cookies, seenRegularHeader, headers)
          case (":status", _) =>
            malformedRequest("Pseudo-header ':status' is for responses only; it cannot appear in a request")

          case ("content-type", ct) =>
            if (contentType.isEmpty) {
              val contentTypeValue = ContentType.parse(ct).right.getOrElse(malformedRequest(s"Invalid content-type: '$ct'"))
              rec(remainingHeaders.tail, method, scheme, authority, pathAndRawQuery, OptionVal.Some(contentTypeValue), contentLength, cookies, true, headers)
            } else malformedRequest("HTTP message must not contain more than one content-type header")

          case ("content-length", length) =>
            if (contentLength == -1) {
              val contentLengthValue = length.toLong
              if (contentLengthValue < 0) malformedRequest("HTTP message must not contain a negative content-length header")
              rec(remainingHeaders.tail, method, scheme, authority, pathAndRawQuery, contentType, contentLengthValue, cookies, true, headers)
            } else malformedRequest("HTTP message must not contain more than one content-length header")

          case ("cookie", value) =>
            // Compress cookie headers as described here https://tools.ietf.org/html/rfc7540#section-8.1.2.5
            val cookiesBuilder = if (cookies == null) {
              new StringBuilder
            } else {
              cookies.append("; ") // Append octets as required by the spec
            }
            cookiesBuilder.append(value)
            rec(remainingHeaders.tail, method, scheme, authority, pathAndRawQuery, contentType, contentLength, cookiesBuilder, true, headers)

          case (name, value) =>
            val httpHeader = parseHeaderPair(httpHeaderParser, name, value)
            validateHeader(httpHeader)
            rec(remainingHeaders.tail, method, scheme, authority, pathAndRawQuery, contentType, contentLength, cookies, true, headers += httpHeader)
        }

      val remainingHeaders = subStream.initialHeaders.keyValuePairs
      if (remainingHeaders.size > serverSettings.parserSettings.maxHeaderCount)
        malformedRequest(s"HTTP message contains more than the configured limit of ${serverSettings.parserSettings.maxHeaderCount} headers")
      else rec(remainingHeaders)
    }
  }

  private[http2] def parseHeaderPair(httpHeaderParser: HttpHeaderParser, name: String, value: String): HttpHeader = {
    // FIXME: later modify by adding HttpHeaderParser.parseHttp2Header that would use (name, value) pair directly
    //        or use a separate, simpler, parser for Http2
    // The odd-looking 'x' below is a by-product of how current parser and HTTP/1.1 work.
    // Without '\r\n\x' (x being any additional byte) parsing will fail. See HttpHeaderParserSpec for examples.
    val concHeaderLine = name + ": " + value + "\r\nx"
    httpHeaderParser.parseHeaderLine(ByteString(concHeaderLine))()
    httpHeaderParser.resultHeader
  }

  private[http2] def checkRequiredPseudoHeader(name: String, value: AnyRef): Unit =
    if (value eq null) malformedRequest(s"Mandatory pseudo-header '$name' missing")
  private[http2] def checkUniquePseudoHeader(name: String, value: AnyRef): Unit =
    if (value ne null) malformedRequest(s"Pseudo-header '$name' must not occur more than once")
  private[http2] def checkNoRegularHeadersBeforePseudoHeader(name: String, seenRegularHeader: Boolean): Unit =
    if (seenRegularHeader) malformedRequest(s"Pseudo-header field '$name' must not appear after a regular header")
  private[http2] def malformedRequest(msg: String): Nothing =
    throw new RuntimeException(s"Malformed request: $msg")
  private[http2] def validateHeader(httpHeader: HttpHeader) = httpHeader.lowercaseName match {
    case "connection" =>
      // https://tools.ietf.org/html/rfc7540#section-8.1.2.2
      malformedRequest("Header 'Connection' must not be used with HTTP/2")
    case "transfer-encoding" =>
      // https://tools.ietf.org/html/rfc7540#section-8.1.2.2
      malformedRequest("Header 'Transfer-Encoding' must not be used with HTTP/2")
    case "te" =>
      // https://tools.ietf.org/html/rfc7540#section-8.1.2.2
      if (httpHeader.value.compareToIgnoreCase("trailers") != 0) malformedRequest(s"Header 'TE' must not contain value other than 'trailers', value was '${httpHeader.value}")
    case _ => // ok
  }

}
