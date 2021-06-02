/*
 * Copyright (C) 2009-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2.hpack

import java.io.IOException
import java.nio.charset.StandardCharsets
import akka.annotation.InternalApi
import akka.http.impl.engine.http2.Http2Protocol.ErrorCode
import akka.http.impl.engine.http2._
import akka.http.impl.engine.http2.RequestParsing.{ checkNoRegularHeadersBeforePseudoHeader, checkUniquePseudoHeader, malformedRequest, parseHeaderPair }
import akka.http.impl.engine.parsing.HttpHeaderParser
import akka.http.scaladsl.model.{ ContentType, IllegalUriException, ParsingException, Uri }
import akka.http.shaded.com.twitter.hpack.HeaderListener
import akka.stream._
import akka.stream.stage.{ GraphStage, GraphStageLogic }
import akka.util.ByteString

import scala.collection.immutable.VectorBuilder
import FrameEvent._
import akka.http.impl.engine.http2.Http2Compliance.Http2ProtocolException

/**
 * INTERNAL API
 *
 * Can be used on server and client side.
 */
@InternalApi
private[http2] class HeaderDecompression(masterHeaderParser: HttpHeaderParser) extends GraphStage[FlowShape[FrameEvent, FrameEvent]] {
  val UTF8 = StandardCharsets.UTF_8
  val US_ASCII = StandardCharsets.US_ASCII

  val eventsIn = Inlet[FrameEvent]("HeaderDecompression.eventsIn")
  val eventsOut = Outlet[FrameEvent]("HeaderDecompression.eventsOut")

  val shape = FlowShape(eventsIn, eventsOut)

  def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new HandleOrPassOnStage[FrameEvent, FrameEvent](shape) {
    val httpHeaderParser = masterHeaderParser.createShallowCopy()
    val decoder = new akka.http.shaded.com.twitter.hpack.Decoder(Http2Protocol.InitialMaxHeaderListSize, Http2Protocol.InitialMaxHeaderTableSize)

    become(Idle)

    // simple state machine
    // Idle: no ongoing HEADERS parsing
    // Receiving headers: waiting for CONTINUATION frame

    private def byteArrayToAsciiString(bs: Array[Byte]): String =
      new String(bs, 0, 0, bs.length)

    def parseAndEmit(streamId: Int, endStream: Boolean, payload: ByteString, prioInfo: Option[PriorityFrame]): Unit = {
      val headers = new VectorBuilder[(String, AnyRef)]
      object Receiver extends HeaderListener {
        def addHeader(nameBytes: Array[Byte], valueBytes: Array[Byte], parsed: AnyRef, sensitive: Boolean): AnyRef = {
          // TODO: optimization: use preallocated strings for well-known names, similar to what happens in HeaderParser
          val name = byteArrayToAsciiString(nameBytes)

          if (parsed ne null) {
            headers += name -> parsed
            parsed
          } else {
            val value = byteArrayToAsciiString(valueBytes)
            name match {
              case "content-type" =>
                val contentTypeValue = ContentType.parse(value).right.getOrElse(malformedRequest(s"Invalid content-type: '$value'"))
                headers += name -> contentTypeValue
                contentTypeValue
              case ":authority" =>
                val authority: Uri.Authority = try {
                  Uri.parseHttp2AuthorityPseudoHeader(value /*FIXME: , mode = serverSettings.parserSettings.uriParsingMode*/ )
                } catch {
                  case IllegalUriException(info) => throw new ParsingException(info)
                }
                headers += name -> authority
                authority
              case ":path" =>
                val newPathAndRawQuery: (Uri.Path, Option[String]) = try {
                  Uri.parseHttp2PathPseudoHeader(value /* FIXME:, mode = serverSettings.parserSettings.uriParsingMode */ )
                } catch {
                  case IllegalUriException(info) => throw new ParsingException(info)
                }
                headers += name -> newPathAndRawQuery
                newPathAndRawQuery
              case "content-length" | "cookie" =>
                headers += name -> value
                value
              case x if x(0) == ':' =>
                headers += name -> value
                value
              case _ =>
                val parsed = parseHeaderPair(httpHeaderParser, name, value)
                headers += name -> parsed
                parsed
            }
          }
        }
      }
      try {
        decoder.decode(ByteStringInputStream(payload), Receiver)
        decoder.endHeaderBlock() // TODO: do we have to check the result here?

        push(eventsOut, ParsedHeadersFrame(streamId, endStream, headers.result(), prioInfo))
      } catch {
        case ex: IOException =>
          // this is signalled by the decoder when it failed, we want to react to this by rendering a GOAWAY frame
          fail(eventsOut, new Http2Compliance.Http2ProtocolException(ErrorCode.COMPRESSION_ERROR, "Decompression failed."))
      }
    }

    object Idle extends State {
      val handleEvent: PartialFunction[FrameEvent, Unit] = {
        case HeadersFrame(streamId, endStream, endHeaders, fragment, prioInfo) =>
          if (endHeaders) parseAndEmit(streamId, endStream, fragment, prioInfo)
          else {
            become(new ReceivingHeaders(streamId, endStream, fragment, prioInfo))
            pull(eventsIn)
          }
        case c: ContinuationFrame =>
          protocolError(s"Received unexpected continuation frame: $c")

        // FIXME: handle SETTINGS frames that change decompression parameters
      }
    }
    class ReceivingHeaders(streamId: Int, endStream: Boolean, initiallyReceivedData: ByteString, priorityInfo: Option[PriorityFrame]) extends State {
      var receivedData = initiallyReceivedData

      val handleEvent: PartialFunction[FrameEvent, Unit] = {
        case ContinuationFrame(`streamId`, endHeaders, payload) =>
          if (endHeaders) {
            parseAndEmit(streamId, endStream, receivedData ++ payload, priorityInfo)
            become(Idle)
          } else {
            receivedData ++= payload
            pull(eventsIn)
          }
        case x => protocolError(s"While waiting for CONTINUATION frame on stream $streamId received unexpected frame $x")
      }
    }

    def protocolError(msg: String): Unit = failStage(new Http2ProtocolException(msg))
  }
}
