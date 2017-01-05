/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2.hpack

import java.nio.charset.Charset

import akka.http.impl.engine.http2.{ ContinuationFrame, FrameEvent, HeadersFrame, ParsedHeadersFrame }
import akka.stream.{ Attributes, FlowShape, Inlet, Outlet }
import akka.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler }
import akka.util.ByteString
import com.twitter.hpack.HeaderListener

import scala.collection.immutable.VectorBuilder

/**
 * INTERNAL API
 *
 * Can be used on server and client side.
 */
private[http2] object HeaderDecompression extends GraphStage[FlowShape[FrameEvent, FrameEvent]] {
  val UTF8 = Charset.forName("utf-8")

  final val maxHeaderSize = 4096
  final val maxHeaderTableSize = 4096

  val eventsIn = Inlet[FrameEvent]("HeaderDecompression.eventsIn")
  val eventsOut = Outlet[FrameEvent]("HeaderDecompression.eventsOut")

  val shape = FlowShape(eventsIn, eventsOut)

  def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    val decoder = new com.twitter.hpack.Decoder(maxHeaderSize, maxHeaderTableSize)
    become(Idle)

    // simple state machine
    // Idle: no ongoing HEADERS parsing
    // Receiving headers: waiting for CONTINUATION frame

    def parseAndEmit(streamId: Int, endStream: Boolean, payload: ByteString): Unit = {
      var headers = new VectorBuilder[(String, String)]
      object Receiver extends HeaderListener {
        def addHeader(name: Array[Byte], value: Array[Byte], sensitive: Boolean): Unit =
          // TODO: optimization: use preallocated strings for well-known names, similar to
          // what happens in HeaderParser
          headers += new String(name, UTF8) → new String(value, UTF8)
      }
      decoder.decode(ByteStringInputStream(payload), Receiver)
      decoder.endHeaderBlock() // TODO: do we have to check the result here?
      push(eventsOut, ParsedHeadersFrame(streamId, endStream, headers.result()))
    }

    object Idle extends State {
      val handleEvent: PartialFunction[FrameEvent, Unit] = {
        case HeadersFrame(streamId, endStream, endHeaders, fragment) ⇒
          if (endHeaders) parseAndEmit(streamId, endStream, fragment)
          else {
            become(new ReceivingHeaders(streamId, endStream, fragment))
            pull(eventsIn)
          }
        case c: ContinuationFrame ⇒
          protocolError(s"Received unexpected continuation frame: $c")
      }
    }
    class ReceivingHeaders(streamId: Int, endStream: Boolean, initiallyReceivedData: ByteString) extends State {
      var receivedData = initiallyReceivedData

      val handleEvent: PartialFunction[FrameEvent, Unit] = {
        case ContinuationFrame(`streamId`, endHeaders, payload) ⇒
          if (endHeaders) {
            parseAndEmit(streamId, endStream, receivedData ++ payload)
            become(Idle)
          } else receivedData ++= payload
        case x ⇒ protocolError(s"While waiting for CONTINUATION frame on stream $streamId received unexpected frame $x")
      }
    }

    def protocolError(msg: String): Unit = failStage(new RuntimeException(msg)) // TODO: replace with right exception type

    def become(state: State): Unit = setHandlers(eventsIn, eventsOut, state)
    abstract class State extends InHandler with OutHandler {
      val handleEvent: PartialFunction[FrameEvent, Unit]

      def onPush(): Unit = {
        val event = grab(eventsIn)
        handleEvent.applyOrElse[FrameEvent, Unit](event, ev ⇒ push(eventsOut, ev))
      }
      def onPull(): Unit = pull(eventsIn)
    }
  }
}