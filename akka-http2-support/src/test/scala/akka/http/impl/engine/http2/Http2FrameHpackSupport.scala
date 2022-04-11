/*
 * Copyright (C) 2020-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model._
import akka.http.shaded.com.twitter.hpack._
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import org.scalatest.concurrent.ScalaFutures

import java.io.ByteArrayInputStream
import scala.collection.immutable.VectorBuilder

/** Helper that allows automatic HPACK encoding/decoding for wire sends / expectations */
trait Http2FrameHpackSupport extends Http2FrameProbeDelegator with Http2FrameSending with HPackEncodingSupport with ScalaFutures {
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

  val decoder = new Decoder(Http2Protocol.InitialMaxHeaderListSize, Http2Protocol.InitialMaxHeaderTableSize)

  def decodeHeaders(bytes: ByteString): Seq[(String, String)] = {
    val bis = new ByteArrayInputStream(bytes.toArray)
    val hs = new VectorBuilder[(String, String)]()

    decoder.decode(bis, new HeaderListener {
      def addHeader(name: String, value: String, parsedValue: AnyRef, sensitive: Boolean): AnyRef = {
        hs += name -> value
        parsedValue
      }
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
