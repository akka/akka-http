/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2
package client

import akka.annotation.InternalApi
import akka.http.impl.engine.http2.RequestParsing._
import akka.http.impl.engine.parsing.HttpHeaderParser
import akka.http.scaladsl.model._

import scala.annotation.tailrec
import scala.collection.immutable.VectorBuilder

@InternalApi
private[http2] object ResponseParsing {
  def parseResponse(httpHeaderParser: HttpHeaderParser): ChunkedHttp2SubStream => HttpResponse = { subStream =>
    @tailrec
    def rec(
      remainingHeaders:  Seq[(String, String)],
      status:            StatusCode                = null,
      contentType:       ContentType               = ContentTypes.`application/octet-stream`,
      contentLength:     Long                      = -1,
      seenRegularHeader: Boolean                   = false,
      headers:           VectorBuilder[HttpHeader] = new VectorBuilder[HttpHeader]
    ): HttpResponse =
      if (remainingHeaders.isEmpty) {
        // https://httpwg.org/specs/rfc7540.html#rfc.section.8.1.2.4: these pseudo header fields are mandatory for a response
        checkRequiredPseudoHeader(":status", status)

        val entity = subStream.createResponseEntity(contentLength, contentType)

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
          val contentType = ContentType.parse(ct).right.getOrElse(malformedRequest(s"Invalid content-type: '$ct'"))
          rec(remainingHeaders.tail, status, contentType, contentLength, seenRegularHeader, headers)

        case ("content-length", length) =>
          val contentLength = length.toLong
          rec(remainingHeaders.tail, status, contentType, contentLength, seenRegularHeader, headers)

        case (name, _) if name.startsWith(":") =>
          malformedRequest(s"Unexpected pseudo-header '$name' in response")

        case (name, value) =>
          val httpHeader = parseHeaderPair(httpHeaderParser, name, value)
          rec(remainingHeaders.tail, status, contentType, contentLength, seenRegularHeader = true, headers += httpHeader)
      }

    rec(subStream.initialHeaders.keyValuePairs)
  }
}
