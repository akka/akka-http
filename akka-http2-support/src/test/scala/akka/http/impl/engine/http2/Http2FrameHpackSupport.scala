/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import java.io.{ ByteArrayInputStream, ByteArrayOutputStream }

import akka.http.impl.util.StringRendering
import akka.http.scaladsl.model.{ ContentType, HttpEntity, HttpHeader, HttpRequest, HttpResponse }
import akka.http.scaladsl.model.headers.RawHeader
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.twitter.hpack.{ Decoder, Encoder, HeaderListener }
import org.scalatest.concurrent.ScalaFutures

import scala.collection.immutable.VectorBuilder

/** Helper that allows automatic HPACK encoding/decoding for wire sends / expectations */
trait Http2FrameHpackSupport extends Http2FrameProbeDelegator with Http2FrameSending with ScalaFutures {
  def sendRequestHEADERS(streamId: Int, request: HttpRequest, endStream: Boolean): Unit =
    sendHEADERS(streamId, endStream = endStream, endHeaders = true, encodeRequestHeaders(request))

  def sendHEADERS(streamId: Int, endStream: Boolean, headers: Seq[HttpHeader]): Unit =
    sendHEADERS(streamId, endStream = endStream, endHeaders = true, encodeHeaders(headers))

  def sendRequest(streamId: Int, request: HttpRequest)(implicit mat: Materializer): Unit = {
    val isEmpty = request.entity.isKnownEmpty
    sendHEADERS(streamId, endStream = isEmpty, endHeaders = true, encodeRequestHeaders(request))

    if (!isEmpty)
      sendDATA(streamId, endStream = true, request.entity.dataBytes.runFold(ByteString.empty)(_ ++ _).futureValue)
  }

  def expectDecodedHEADERS(streamId: Int, endStream: Boolean = true): HttpResponse = {
    val headerBlockBytes = expectHeaderBlock(streamId, endStream)
    val decoded = decodeHeadersToResponse(headerBlockBytes)
    // filter date to make it easier to test
    decoded.withHeaders(decoded.headers.filterNot(h => h.is("date")))
  }

  def expectDecodedResponseHEADERSPairs(streamId: Int, endStream: Boolean = true): Seq[(String, String)] = {
    val headerBlockBytes = expectHeaderBlock(streamId, endStream)
    // filter date to make it easier to test
    decodeHeaders(headerBlockBytes).filter(_._1 != "date")
  }

  val encoder = new Encoder(Http2Protocol.InitialMaxHeaderTableSize)

  def encodeRequestHeaders(request: HttpRequest): ByteString =
    encodeHeaderPairs(headerPairsForRequest(request))

  def encodeHeaders(headers: Seq[HttpHeader]): ByteString =
    encodeHeaderPairs(headerPairsForHeaders(headers))

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

    def encode(name: String, value: String): Unit = encoder.encodeHeader(bos, name.getBytes, value.getBytes, false)

    headerPairs.foreach((encode _).tupled)

    ByteString(bos.toByteArray)
  }

  val decoder = new Decoder(Http2Protocol.InitialMaxHeaderListSize, Http2Protocol.InitialMaxHeaderTableSize)

  def decodeHeaders(bytes: ByteString): Seq[(String, String)] = {
    val bis = new ByteArrayInputStream(bytes.toArray)
    val hs = new VectorBuilder[(String, String)]()

    decoder.decode(bis, new HeaderListener {
      def addHeader(name: Array[Byte], value: Array[Byte], sensitive: Boolean): Unit =
        hs += new String(name) -> new String(value)
    })
    hs.result()
  }
  def decodeHeadersToResponse(bytes: ByteString): HttpResponse =
    decodeHeaders(bytes).foldLeft(HttpResponse())((old, header) => header match {
      case (":status", value)                             => old.withStatus(value.toInt)
      case ("content-length", value) if value.toLong == 0 => old.withEntity(HttpEntity.Empty)
      case ("content-length", value)                      => old.withEntity(HttpEntity.Default(old.entity.contentType, value.toLong, Source.empty))
      case ("content-type", value)                        => old.withEntity(old.entity.withContentType(ContentType.parse(value).right.get))
      case (name, value)                                  => old.addHeader(RawHeader(name, value)) // FIXME: decode to modeled headers
    })
}
