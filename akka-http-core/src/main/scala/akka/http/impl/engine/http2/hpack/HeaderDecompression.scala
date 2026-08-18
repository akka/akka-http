/*
 * Copyright (C) 2009-2025 Lightbend Inc. <https://akka.io>
 */

package akka.http.impl.engine.http2.hpack

import akka.annotation.InternalApi
import akka.http.impl.engine.http2.FrameEvent._
import akka.http.impl.engine.http2.Http2Compliance.Http2ProtocolException
import akka.http.impl.engine.http2.Http2Protocol.ErrorCode
import akka.http.impl.engine.http2.RequestParsing.parseHeaderPair
import akka.http.impl.engine.http2._
import akka.http.impl.engine.parsing.HttpHeaderParser
import akka.http.scaladsl.model.{ ErrorInfo, ParsingException }
import akka.http.scaladsl.settings.{ Http2CommonSettings, ParserSettings }
import akka.http.shaded.com.twitter.hpack.HeaderListener
import akka.stream._
import akka.stream.stage.{ GraphStage, GraphStageLogic }
import akka.util.ByteString

import java.io.IOException
import java.nio.charset.StandardCharsets
import scala.collection.immutable.VectorBuilder

/**
 * INTERNAL API
 *
 * Can be used on server and client side.
 */
@InternalApi
private[http2] final class HeaderDecompression(masterHeaderParser: HttpHeaderParser, parserSettings: ParserSettings, http2Settings: Http2CommonSettings) extends GraphStage[FlowShape[FrameEvent, FrameEvent]] {
  val UTF8 = StandardCharsets.UTF_8
  val US_ASCII = StandardCharsets.US_ASCII

  val eventsIn = Inlet[FrameEvent]("HeaderDecompression.eventsIn")
  val eventsOut = Outlet[FrameEvent]("HeaderDecompression.eventsOut")

  val shape = FlowShape(eventsIn, eventsOut)

  def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new HandleOrPassOnStage[FrameEvent, FrameEvent](shape) {
    val httpHeaderParser = masterHeaderParser.createShallowCopy()
    // bounds the decoded header list, which can be far larger than the compressed header block it came from.
    // The compressed block itself is bounded separately while accumulating CONTINUATION frames below.
    val decoder = new akka.http.shaded.com.twitter.hpack.Decoder(http2Settings.maxHeaderListSize, Http2Protocol.InitialMaxHeaderTableSize)

    become(Idle)

    // simple state machine
    // Idle: no ongoing HEADERS parsing
    // Receiving headers: waiting for CONTINUATION frame

    def parseAndEmit(streamId: Int, endStream: Boolean, payload: ByteString, prioInfo: Option[PriorityFrame]): Unit = {
      val headers = new VectorBuilder[(String, AnyRef)]
      var parseError: Option[ErrorInfo] = None
      object Receiver extends HeaderListener {
        def addHeader(name: String, value: String, parsed: AnyRef, sensitive: Boolean): AnyRef = {
          if (parsed ne null) {
            headers += name -> parsed
            parsed
          } else {
            import Http2HeaderParsing._
            def handle(parsed: AnyRef): AnyRef = {
              headers += name -> parsed
              parsed
            }

            try {
              name match {
                case "content-type"   => handle(ContentType.parse(name, value, parserSettings))
                case ":authority"     => handle(Authority.parse(name, value, parserSettings))
                case ":path"          => handle(PathAndQuery.parse(name, value, parserSettings))
                case ":method"        => handle(Method.parse(name, value, parserSettings))
                case ":scheme"        => handle(Scheme.parse(name, value, parserSettings))
                case "content-length" => handle(ContentLength.parse(name, value, parserSettings))
                case "cookie"         => handle(Cookie.parse(name, value, parserSettings))
                case x if x(0) == ':' => handle(value)
                case _ =>
                  // cannot use OtherHeader.parse because that doesn't has access to header parser
                  val header = parseHeaderPair(httpHeaderParser, name, value)
                  RequestParsing.validateHeader(header)
                  handle(header)
              }
            } catch {
              case ex: ParsingException =>
                // the remainder of the block still has to be decoded to keep the HPACK dynamic table in sync
                // with the peer, so remember the first error rather than letting it abort decoding
                if (parseError.isEmpty) parseError = Some(ex.info)
                null // nothing is cached for this header, so a later reference to it fails again
            }
          }
        }
      }
      try {
        decoder.decode(ByteStringInputStream(payload), Receiver)
        // the decoder silently skips header fields once maxHeaderListSize is exceeded and only reports that
        // here, so not checking would hand a request with arbitrary headers missing to the application
        if (decoder.endHeaderBlock())
          headerLimitExceeded(s"Decoded header list for stream $streamId exceeded configured maximum of ${http2Settings.maxHeaderListSize} bytes")
        else parseError match {
          // push details further and let RequestErrorFlow handle responding with bad request
          case Some(_) => push(eventsOut, ParsedHeadersFrame(streamId, endStream, Seq.empty, prioInfo, parseError))
          case None    => push(eventsOut, ParsedHeadersFrame(streamId, endStream, headers.result(), prioInfo, None))
        }
      } catch {
        case _: IOException =>
          // this is signalled by the decoder when it failed, we want to react to this by rendering a GOAWAY frame
          fail(eventsOut, new Http2Compliance.Http2ProtocolException(ErrorCode.COMPRESSION_ERROR, "Decompression failed."))
      }
    }

    object Idle extends State {
      val handleEvent: PartialFunction[FrameEvent, Unit] = {
        case HeadersFrame(streamId, endStream, endHeaders, fragment, prioInfo) =>
          if (fragment.size > http2Settings.maxHeaderBlockSize)
            headerBlockSizeExceeded(streamId)
          else if (endHeaders) parseAndEmit(streamId, endStream, fragment, prioInfo)
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
      var continuationFrames = 0

      val handleEvent: PartialFunction[FrameEvent, Unit] = {
        case ContinuationFrame(`streamId`, endHeaders, payload) =>
          continuationFrames += 1
          if (continuationFrames > http2Settings.maxContinuationFrames)
            headerLimitExceeded(s"Received more than ${http2Settings.maxContinuationFrames} CONTINUATION frames for stream $streamId")
          else if (receivedData.size.toLong + payload.size > http2Settings.maxHeaderBlockSize)
            headerBlockSizeExceeded(streamId)
          else if (endHeaders) {
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

    // connection error rather than stream error: the offending block is either not decoded at all, leaving the
    // HPACK dynamic table out of sync with the peer, or decoded with fields missing
    def headerLimitExceeded(msg: String): Unit = failStage(new Http2ProtocolException(ErrorCode.ENHANCE_YOUR_CALM, msg))

    def headerBlockSizeExceeded(streamId: Int): Unit =
      headerLimitExceeded(s"Header block for stream $streamId exceeded configured maximum of ${http2Settings.maxHeaderBlockSize} bytes")
  }
}
