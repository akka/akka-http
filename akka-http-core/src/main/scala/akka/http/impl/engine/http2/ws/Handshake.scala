/*
 * Copyright (C) 2009-2026 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.http.impl.engine.http2.ws

import akka.annotation.InternalApi
import akka.event.LoggingAdapter
import akka.http.impl.engine.server.UpgradeToOtherProtocolResponseHeader
import akka.http.impl.engine.ws.FrameEvent
import akka.http.impl.engine.ws.Handshake.ConnectionUpgradeHeader
import akka.http.impl.engine.ws.Handshake.UpgradeHeader
import akka.http.impl.engine.ws.{ Handshake => Http1Handshake }
import akka.http.impl.engine.ws.UpgradeToWebSocketLowLevel
import akka.http.impl.engine.ws.WebSocket
import akka.http.scaladsl.model.HttpHeader
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.Connection
import akka.http.scaladsl.model.headers.Upgrade
import akka.http.scaladsl.model.headers.`Sec-WebSocket-Accept`
import akka.http.scaladsl.model.headers.`Sec-WebSocket-Key`
import akka.http.scaladsl.model.headers.`Sec-WebSocket-Protocol`
import akka.http.scaladsl.model.headers.`Sec-WebSocket-Version`
import akka.http.scaladsl.model.ws.Message
import akka.http.scaladsl.settings.WebSocketSettings
import akka.stream.FlowShape
import akka.stream.Graph
import akka.util.OptionVal

import scala.collection.immutable.Seq

@InternalApi
private[http2] object Handshake {

  object Server {
    /**
     *  Validates a HTTP/2 client WebSocket handshake. Returns either `OptionVal.Some(UpgradeToWebSocketLowLevel)` or
     *  `OptionVal.None`
     *
     *  From: https://www.rfc-editor.org/rfc/rfc8441#section-5
     *
     *  * The scheme of the target URI (Section 5.1 of [RFC7230]) MUST be
     *    "https" for "wss"-schemed WebSockets and "http" for "ws"-schemed
     *     WebSockets.  The remainder of the target URI is the same as the
     *     WebSocket URI.  The WebSocket URI is still used for proxy
     *     autoconfiguration.  The security requirements for the HTTP/2
     *     connection used by this specification are established by [RFC7540]
     *     for https requests and [RFC8164] for http requests.
     *
     *  * [RFC6455] requires the use of Connection and Upgrade header fields
     *    that are not part of HTTP/2.  They MUST NOT be included in the
     *    CONNECT request defined here.
     *
     *  * [RFC6455] requires the use of a Host header field that is also not
     *    part of HTTP/2.  The Host information is conveyed as part of the
     *    :authority pseudo-header field, which is required on every HTTP/2
     *    transaction.
     */
    def websocketUpgrade(headers: Vector[HttpHeader], settings: WebSocketSettings, log: LoggingAdapter): OptionVal[UpgradeToWebSocketLowLevel] = {
      // notes on Headers that re REQUIRE to be present here:
      // - Host header is validated in general HTTP logic - FIXME verify it is
      // - Origin header is optional and, if required, should be validated
      //   on higher levels (routing, application logic)
      //
      // TODO See #18709 Extension support is optional in WS and currently unsupported.
      //
      // these are not needed directly, we verify their presence and correctness only:
      // - Upgrade
      // - Connection
      // - `Sec-WebSocket-Version`
      def hasAllRequiredWebsocketUpgradeHeaders: Boolean = {
        // single-pass through the headers list while collecting all needed requirements
        // this way we avoid scanning the requirements list 3 times (as we would with collect/find)
        val it = headers.iterator
        var requirementsMet = 0
        val targetRequirements = 3
        while (it.hasNext && (requirementsMet != targetRequirements)) it.next() match {
          case u: Upgrade                 => if (u.hasWebSocket) requirementsMet += 1 // FIXME should not be present
          case c: Connection              => if (c.hasUpgrade) requirementsMet += 1 // FIXME should not be present
          case v: `Sec-WebSocket-Version` => if (v.hasVersion(Http1Handshake.CurrentWebSocketVersion)) requirementsMet += 1
          case _                          => // continue...
        }
        requirementsMet == targetRequirements
      }

      val protocol = HttpHeader.fastFind(classOf[`Sec-WebSocket-Protocol`], headers)

      val clientSupportedSubprotocols = protocol match {
        case OptionVal.Some(p) => p.protocols
        case _                 => Nil
      }

      val header = new UpgradeToWebSocketLowLevel {
        def requestedProtocols: Seq[String] = clientSupportedSubprotocols

        def handle(handler: Either[Graph[FlowShape[FrameEvent, FrameEvent], Any], Graph[FlowShape[Message, Message], Any]], subprotocol: Option[String]): HttpResponse = {
          require(
            subprotocol.forall(chosen => clientSupportedSubprotocols.contains(chosen)),
            s"Tried to choose invalid subprotocol '$subprotocol' which wasn't offered by the client: [${requestedProtocols.mkString(", ")}]")
          buildResponse(handler, subprotocol, settings, log)
        }

        def handleFrames(handlerFlow: Graph[FlowShape[FrameEvent, FrameEvent], Any], subprotocol: Option[String]): HttpResponse =
          handle(Left(handlerFlow), subprotocol)

        override def handleMessages(handlerFlow: Graph[FlowShape[Message, Message], Any], subprotocol: Option[String] = None): HttpResponse =
          handle(Right(handlerFlow), subprotocol)
      }
      OptionVal.Some(header)
    }

    def buildResponse(handler: Either[Graph[FlowShape[FrameEvent, FrameEvent], Any], Graph[FlowShape[Message, Message], Any]], subprotocol: Option[String], settings: WebSocketSettings, log: LoggingAdapter): HttpResponse = {
      val frameHandler = handler match {
        case Left(frameHandler) => frameHandler
        case Right(messageHandler) =>
          WebSocket.stack(serverSide = true, settings, log = log).join(messageHandler)
      }

      HttpResponse(
        StatusCodes.OK,
        subprotocol.map(p => `Sec-WebSocket-Protocol`(Seq(p))).toList :::
          List(
            UpgradeToOtherProtocolResponseHeader(WebSocket.framing.join(frameHandler))))
    }
  }

}
