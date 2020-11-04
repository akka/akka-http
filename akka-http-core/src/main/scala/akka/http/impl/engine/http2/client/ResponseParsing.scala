/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2
package client

import akka.annotation.InternalApi
import akka.http.impl.engine.http2.RequestParsing._
import akka.http.impl.engine.parsing.HttpHeaderParser
import akka.http.scaladsl.model._
import akka.util.OptionVal

import scala.annotation.tailrec
import scala.collection.immutable.VectorBuilder

@InternalApi
private[http2] object ResponseParsing {
  def parseResponse(httpHeaderParser: HttpHeaderParser): Http2SubStream => HttpResponse = { subStream =>
    @tailrec
    def rec(
      remainingHeaders:  Seq[(String, String)],
      status:            StatusCode                = null,
      contentType:       OptionVal[ContentType]    = OptionVal.None,
      contentLength:     Long                      = -1,
      seenRegularHeader: Boolean                   = false,
      headers:           VectorBuilder[HttpHeader] = new VectorBuilder[HttpHeader]
    ): HttpResponse =
      if (remainingHeaders.isEmpty) {
        // https://httpwg.org/specs/rfc7540.html#rfc.section.8.1.2.4: these pseudo header fields are mandatory for a response
        checkRequiredPseudoHeader(":status", status)

        val entity = subStream.createEntity(contentLength, contentType.getOrElse(ContentTypes.`application/octet-stream`))

        HttpResponse(
          status = status,
          headers = headers.result(),
          entity = entity,
          HttpProtocols.`HTTP/2.0`
        ).withAttributes(subStream.correlationAttributes)
      } else remainingHeaders.head match {
        case (":status", statusCodeValue) =>
          checkUniquePseudoHeader(":status", status)
          checkNoRegularHeadersBeforePseudoHeader(":status", seenRegularHeader)
          rec(remainingHeaders.tail, statusCodeValue.toInt, contentType, contentLength, seenRegularHeader, headers)

        case ("content-type", ct) =>
          if (contentType.isEmpty) {
            val contentTypeValue = ContentType.parse(ct).right.getOrElse(malformedRequest(s"Invalid content-type: '$ct'"))
            rec(remainingHeaders.tail, status, OptionVal.Some(contentTypeValue), contentLength, seenRegularHeader, headers)
          } else malformedRequest("HTTP message must not contain more than one content-type header")

        case ("content-length", length) =>
          if (contentLength == -1) {
            val contentLengthValue = length.toLong
            if (contentLengthValue < 0) malformedRequest("HTTP message must not contain a negative content-length header")
            rec(remainingHeaders.tail, status, contentType, contentLengthValue, seenRegularHeader, headers)
          } else malformedRequest("HTTP message must not contain more than one content-length header")

        case (name, _) if name.startsWith(":") =>
          malformedRequest(s"Unexpected pseudo-header '$name' in response")

        case (name, value) =>
          val httpHeader = parseHeaderPair(httpHeaderParser, name, value)
          validateHeader(httpHeader)
          rec(remainingHeaders.tail, status, contentType, contentLength, seenRegularHeader = true, headers += httpHeader)
      }

    rec(subStream.initialHeaders.keyValuePairs)
  }
}
