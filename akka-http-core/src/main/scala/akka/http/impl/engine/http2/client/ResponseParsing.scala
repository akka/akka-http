/*
 * Copyright (C) 2020-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2
package client

import akka.annotation.InternalApi
import akka.http.impl.engine.http2.Http2Compliance.Http2ProtocolException
import akka.http.impl.engine.http2.RequestParsing._
import akka.http.impl.engine.parsing.HttpHeaderParser
import akka.http.impl.engine.server.HttpAttributes
import akka.http.scaladsl.model
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.`Tls-Session-Info`
import akka.http.scaladsl.settings.ParserSettings
import akka.stream.Attributes
import akka.util.OptionVal

import javax.net.ssl.SSLSession
import scala.annotation.tailrec
import scala.collection.immutable.VectorBuilder

@InternalApi
private[http2] object ResponseParsing {
  def parseResponse(httpHeaderParser: HttpHeaderParser, settings: ParserSettings, attributes: Attributes): Http2SubStream => HttpResponse = { subStream =>

    val tlsSessionInfoHeader: Option[`Tls-Session-Info`] =
      if (settings.includeTlsSessionInfoHeader) {
        attributes.get[HttpAttributes.TLSSessionInfo].map(sslSessionInfo =>
          model.headers.`Tls-Session-Info`(sslSessionInfo.session))
      } else None

    val sslSessionAttribute: Option[SSLSession] =
      if (settings.includeSslSessionAttribute)
        attributes.get[HttpAttributes.TLSSessionInfo].map(_.session)
      else
        None

    @tailrec
    def rec(
      remainingHeaders:  Seq[(String, AnyRef)],
      status:            StatusCode                = null,
      contentType:       OptionVal[ContentType]    = OptionVal.None,
      contentLength:     Long                      = -1,
      seenRegularHeader: Boolean                   = false,
      headers:           VectorBuilder[HttpHeader] = new VectorBuilder[HttpHeader]
    ): HttpResponse =
      if (remainingHeaders.isEmpty) {
        // https://httpwg.org/specs/rfc7540.html#rfc.section.8.1.2.4: these pseudo header fields are mandatory for a response
        checkRequiredPseudoHeader(":status", status)

        val entity = subStream.createEntity(contentLength, contentType)

        // user access to tls session info
        if (tlsSessionInfoHeader.isDefined) headers += tlsSessionInfoHeader.get

        val response = HttpResponse(
          status = status,
          headers = headers.result(),
          entity = entity,
          HttpProtocols.`HTTP/2.0`
        ).withAttributes(subStream.correlationAttributes)
        sslSessionAttribute match {
          case Some(sslSession) => response.addAttribute(AttributeKeys.sslSession, SslSessionInfo(sslSession))
          case None             => response
        }

      } else remainingHeaders.head match {
        case (":status", statusCodeValue: String) =>
          checkUniquePseudoHeader(":status", status)
          checkNoRegularHeadersBeforePseudoHeader(":status", seenRegularHeader)
          rec(remainingHeaders.tail, statusCodeValue.toInt, contentType, contentLength, seenRegularHeader, headers)

        case ("content-type", contentTypeValue: ContentType) =>
          if (contentType.isEmpty)
            rec(remainingHeaders.tail, status, OptionVal.Some(contentTypeValue), contentLength, seenRegularHeader, headers)
          else
            malformedResponse("HTTP message must not contain more than one content-type header")

        case ("content-type", ct: String) =>
          if (contentType.isEmpty) {
            val contentTypeValue = ContentType.parse(ct).right.getOrElse(malformedResponse(s"Invalid content-type: '$ct'"))
            rec(remainingHeaders.tail, status, OptionVal.Some(contentTypeValue), contentLength, seenRegularHeader, headers)
          } else malformedResponse("HTTP message must not contain more than one content-type header")

        case ("content-length", length: String) =>
          if (contentLength == -1) {
            val contentLengthValue = length.toLong
            if (contentLengthValue < 0) malformedResponse("HTTP message must not contain a negative content-length header")
            rec(remainingHeaders.tail, status, contentType, contentLengthValue, seenRegularHeader, headers)
          } else malformedResponse("HTTP message must not contain more than one content-length header")

        case (name, _) if name.startsWith(":") =>
          malformedResponse(s"Unexpected pseudo-header '$name' in response")

        case (_, httpHeader: HttpHeader) =>
          rec(remainingHeaders.tail, status, contentType, contentLength, seenRegularHeader = true, headers += httpHeader)

        case (name, value: String) =>
          val httpHeader = parseHeaderPair(httpHeaderParser, name, value)
          validateHeader(httpHeader)
          rec(remainingHeaders.tail, status, contentType, contentLength, seenRegularHeader = true, headers += httpHeader)

        case _ => throw new IllegalStateException("Unexpected remaining header") // compiler completeness check pleaser
      }

    rec(subStream.initialHeaders.keyValuePairs)
  }

  private def malformedResponse(msg: String): Nothing =
    throw throw new Http2ProtocolException(s"Malformed response: $msg")
}
