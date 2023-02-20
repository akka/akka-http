/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
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

import scala.annotation.nowarn
import scala.annotation.tailrec
import scala.collection.immutable.VectorBuilder
import scala.util.control.NoStackTrace

/**
 * INTERNAL API
 */
@InternalApi
private[http2] object RequestParsing {

  sealed trait ParseRequestResult
  final case class OkRequest(request: HttpRequest) extends ParseRequestResult
  final case class BadRequest(info: ErrorInfo, streamId: Int) extends ParseRequestResult

  @nowarn("msg=use remote-address-attribute instead")
  def parseRequest(httpHeaderParser: HttpHeaderParser, serverSettings: ServerSettings, streamAttributes: Attributes): Http2SubStream => ParseRequestResult = {

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

    val baseAttributes = {
      var map = Map.empty[AttributeKey[_], Any]
      map = sslSessionAttribute match {
        case Some(sslSession) => map.updated(AttributeKeys.sslSession, SslSessionInfo(sslSession))
        case None             => map
      }
      map = remoteAddressAttribute match {
        case Some(remoteAddress) => map.updated(AttributeKeys.remoteAddress, remoteAddress)
        case None                => map
      }
      map
    }

    { subStream =>
      def createRequest(
        method:          HttpMethod,
        scheme:          String,
        authority:       Uri.Authority,
        pathAndRawQuery: (Uri.Path, Option[String]),
        contentType:     OptionVal[ContentType],
        contentLength:   Long,
        cookies:         StringBuilder,
        headers:         VectorBuilder[HttpHeader]
      ): HttpRequest = {
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
        val attributes = baseAttributes.updated(Http2.streamId, subStream.streamId)

        new HttpRequest(method, uri, headers.result(), attributes, entity, HttpProtocols.`HTTP/2.0`)
      }

      @tailrec
      def rec(
        incomingHeaders:   IndexedSeq[(String, AnyRef)],
        offset:            Int,
        method:            HttpMethod                   = null,
        scheme:            String                       = null,
        authority:         Uri.Authority                = null,
        pathAndRawQuery:   (Uri.Path, Option[String])   = null,
        contentType:       OptionVal[ContentType]       = OptionVal.None,
        contentLength:     Long                         = -1,
        cookies:           StringBuilder                = null,
        seenRegularHeader: Boolean                      = false,
        headers:           VectorBuilder[HttpHeader]    = new VectorBuilder[HttpHeader]
      ): HttpRequest =
        if (offset == incomingHeaders.size) createRequest(method, scheme, authority, pathAndRawQuery, contentType, contentLength, cookies, headers)
        else {
          import hpack.Http2HeaderParsing._
          val (name, value) = incomingHeaders(offset)
          name match {
            case ":scheme" =>
              checkUniquePseudoHeader(":scheme", scheme)
              checkNoRegularHeadersBeforePseudoHeader(":scheme", seenRegularHeader)
              rec(incomingHeaders, offset + 1, method, Scheme.get(value), authority, pathAndRawQuery, contentType, contentLength, cookies, seenRegularHeader, headers)

            case ":method" =>
              checkUniquePseudoHeader(":method", method)
              checkNoRegularHeadersBeforePseudoHeader(":method", seenRegularHeader)

              rec(incomingHeaders, offset + 1, Method.get(value), scheme, authority, pathAndRawQuery, contentType, contentLength, cookies, seenRegularHeader, headers)

            case ":path" =>
              checkUniquePseudoHeader(":path", pathAndRawQuery)
              checkNoRegularHeadersBeforePseudoHeader(":path", seenRegularHeader)
              rec(incomingHeaders, offset + 1, method, scheme, authority, PathAndQuery.get(value), contentType, contentLength, cookies, seenRegularHeader, headers)

            case ":authority" =>
              checkUniquePseudoHeader(":authority", authority)
              checkNoRegularHeadersBeforePseudoHeader(":authority", seenRegularHeader)

              rec(incomingHeaders, offset + 1, method, scheme, Authority.get(value), pathAndRawQuery, contentType, contentLength, cookies, seenRegularHeader, headers)

            case "content-type" =>
              if (contentType.isEmpty)
                rec(incomingHeaders, offset + 1, method, scheme, authority, pathAndRawQuery, OptionVal.Some(ContentType.get(value)), contentLength, cookies, true, headers)
              else
                parseError("HTTP message must not contain more than one content-type header", "content-type")

            case ":status" =>
              parseError("Pseudo-header ':status' is for responses only; it cannot appear in a request", ":status")

            case "content-length" =>
              if (contentLength == -1) {
                val contentLengthValue = ContentLength.get(value).toLong
                if (contentLengthValue < 0) parseError("HTTP message must not contain a negative content-length header", "content-length")
                rec(incomingHeaders, offset + 1, method, scheme, authority, pathAndRawQuery, contentType, contentLengthValue, cookies, true, headers)
              } else parseError("HTTP message must not contain more than one content-length header", "content-length")

            case "cookie" =>
              // Compress cookie headers as described here https://tools.ietf.org/html/rfc7540#section-8.1.2.5
              val cookiesBuilder = if (cookies == null) {
                new StringBuilder
              } else {
                cookies.append("; ") // Append octets as required by the spec
              }
              cookiesBuilder.append(Cookie.get(value))
              rec(incomingHeaders, offset + 1, method, scheme, authority, pathAndRawQuery, contentType, contentLength, cookiesBuilder, true, headers)

            case _ =>
              rec(incomingHeaders, offset + 1, method, scheme, authority, pathAndRawQuery, contentType, contentLength, cookies, true, headers += OtherHeader.get(value))
          }
        }

      try {
        subStream.initialHeaders.headerParseErrorDetails match {
          case Some(details) =>
            // header errors already found in decompression
            BadRequest(details, subStream.streamId)
          case None =>
            val incomingHeaders = subStream.initialHeaders.keyValuePairs.toIndexedSeq
            if (incomingHeaders.size > serverSettings.parserSettings.maxHeaderCount)
              parseError(s"HTTP message contains more than the configured limit of ${serverSettings.parserSettings.maxHeaderCount} headers")
            else OkRequest(rec(incomingHeaders, 0))
        }
      } catch {
        case bre: ParsingException => BadRequest(bre.info, subStream.streamId)
      }
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
    if (value eq null) protocolError(s"Mandatory pseudo-header '$name' missing")
  private[http2] def checkUniquePseudoHeader(name: String, value: AnyRef): Unit =
    if (value ne null) protocolError(s"Pseudo-header '$name' must not occur more than once")
  private[http2] def checkNoRegularHeadersBeforePseudoHeader(name: String, seenRegularHeader: Boolean): Unit =
    if (seenRegularHeader) parseError(s"Pseudo-header field '$name' must not appear after a regular header", name)

  private[http2] def validateHeader(httpHeader: HttpHeader) = httpHeader.lowercaseName match {
    case "connection" =>
      // https://tools.ietf.org/html/rfc7540#section-8.1.2.2
      parseError("Header 'Connection' must not be used with HTTP/2", "Connection")
    case "transfer-encoding" =>
      // https://tools.ietf.org/html/rfc7540#section-8.1.2.2
      parseError("Header 'Transfer-Encoding' must not be used with HTTP/2", "Transfer-encoding")
    case "te" =>
      // https://tools.ietf.org/html/rfc7540#section-8.1.2.2
      if (httpHeader.value.compareToIgnoreCase("trailers") != 0) parseError(s"Header 'TE' must not contain value other than 'trailers', value was '${httpHeader.value}", "TE")
    case _ => // ok
  }

  // parse errors lead to BadRequest response while Protocol
  private[http2] def protocolError(summary: String): Nothing =
    throw new Http2Compliance.Http2ProtocolException(s"Malformed request: $summary")

  private[http2] def parseError(summary: String, headerName: String): Nothing =
    throw new ParsingException(ErrorInfo(s"Malformed request: $summary").withErrorHeaderName(headerName)) with NoStackTrace

  private def parseError(summary: String): Nothing =
    throw new ParsingException(ErrorInfo(s"Malformed request: $summary")) with NoStackTrace

}
