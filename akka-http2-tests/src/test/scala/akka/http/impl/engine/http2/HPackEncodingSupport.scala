/*
 * Copyright (C) 2021-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.http.impl.util.StringRendering
import akka.http.scaladsl.model.{ HttpHeader, HttpRequest, HttpResponse }
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.shaded.com.twitter.hpack.Encoder
import akka.util.ByteString

import java.io.ByteArrayOutputStream

/** Helps with a encoding headers to HPACK from Akka HTTP model */
trait HPackEncodingSupport {
  lazy val encoder = new Encoder(Http2Protocol.InitialMaxHeaderTableSize)

  def encodeRequestHeaders(request: HttpRequest): ByteString =
    encodeHeaderPairs(headerPairsForRequest(request))

  def encodeHeaders(headers: Seq[HttpHeader]): ByteString =
    encodeHeaderPairs(headerPairsForHeaders(headers))

  def headersForRequest(request: HttpRequest): Seq[HttpHeader] =
    headerPairsForRequest(request).map {
      case (name, value) =>
        val header: HttpHeader = RawHeader(name, value)
        header
    }

  def headersForResponse(response: HttpResponse): Seq[HttpHeader] =
    Seq(
      RawHeader(":status", response.status.intValue.toString),
      RawHeader("content-type", response.entity.contentType.render(new StringRendering).get)
    ) ++ response.headers.filter(_.renderInResponses)

  def headerPairsForRequest(request: HttpRequest): Seq[(String, String)] =
    Seq(
      ":method" -> request.method.value,
      ":scheme" -> request.uri.scheme.toString,
      ":path" -> request.uri.path.toString,
      ":authority" -> request.uri.authority.toString.drop(2),
      "content-type" -> request.entity.contentType.render(new StringRendering).get
    ) ++
      request.entity.contentLengthOption.flatMap {
        case len if len != 0 => Some("content-length" -> len.toString)
        case _               => None
      }.toSeq ++
      headerPairsForHeaders(request.headers.filter(_.renderInRequests))

  def headerPairsForHeaders(headers: Seq[HttpHeader]): Seq[(String, String)] =
    headers.map(h => h.lowercaseName -> h.value)

  def encodeHeaderPairs(headerPairs: Seq[(String, String)]): ByteString = {
    val bos = new ByteArrayOutputStream()

    def encode(name: String, value: String): Unit = encoder.encodeHeader(bos, name, value, false)

    headerPairs.foreach((encode _).tupled)

    ByteString(bos.toByteArray)
  }
}
