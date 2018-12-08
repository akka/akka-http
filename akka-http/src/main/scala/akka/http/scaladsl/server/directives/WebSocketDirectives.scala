/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.server
package directives

import scala.collection.immutable

import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.ws._
import akka.stream.scaladsl.Flow

/**
 * @groupname websocket WebSocket directives
 * @groupprio websocket 230
 */
trait WebSocketDirectives {
  import BasicDirectives._
  import HeaderDirectives._
  import RouteDirectives._

  /**
   * Extract the [[UpgradeToWebSocket]] header if existent. Rejects with an [[ExpectedWebSocketRequestRejection]], otherwise.
   *
   * @group websocket
   */
  def extractUpgradeToWebSocket: Directive1[UpgradeToWebSocket] =
    optionalHeaderValueByType[UpgradeToWebSocket](()).flatMap {
      case Some(upgrade) ⇒ provide(upgrade)
      case None          ⇒ reject(ExpectedWebSocketRequestRejection)
    }

  /**
   * Extract the list of WebSocket subprotocols as offered by the client in the [[Sec-WebSocket-Protocol]] header if
   * this is a WebSocket request. Rejects with an [[ExpectedWebSocketRequestRejection]], otherwise.
   *
   * @group websocket
   */
  def extractOfferedWsProtocols: Directive1[immutable.Seq[String]] = extractUpgradeToWebSocket.map(_.requestedProtocols)

  /**
   * Handles WebSocket requests with the given handler and rejects other requests with an
   * [[ExpectedWebSocketRequestRejection]].
   *
   * @group websocket
   */
  def handleWebSocketMessages(handler: Flow[Message, Message, Any]): Route =
    handleWebSocketMessagesForOptionalProtocol(handler, None)

  /**
   * Handles WebSocket requests with the given handler if the given subprotocol is offered in the request and
   * rejects other requests with an [[ExpectedWebSocketRequestRejection]] or an [[UnsupportedWebSocketSubprotocolRejection]].
   *
   * @group websocket
   */
  def handleWebSocketMessagesForProtocol(handler: Flow[Message, Message, Any], subprotocol: String): Route =
    handleWebSocketMessagesForOptionalProtocol(handler, Some(subprotocol))

  /**
   * Handles WebSocket requests with the given handler and rejects other requests with an
   * [[ExpectedWebSocketRequestRejection]].
   *
   * If the `subprotocol` parameter is None any WebSocket request is accepted. If the `subprotocol` parameter is
   * `Some(protocol)` a WebSocket request is only accepted if the list of subprotocols supported by the client (as
   * announced in the WebSocket request) contains `protocol`. If the client did not offer the protocol in question
   * the request is rejected with an [[UnsupportedWebSocketSubprotocolRejection]] rejection.
   *
   * To support several subprotocols you may chain several `handleWebSocketMessagesForOptionalProtocol` routes.
   *
   * @group websocket
   */
  def handleWebSocketMessagesForOptionalProtocol(handler: Flow[Message, Message, Any], subprotocol: Option[String]): Route =
    extractUpgradeToWebSocket { upgrade ⇒
      if (subprotocol.forall(sub ⇒ upgrade.requestedProtocols.exists(_ equalsIgnoreCase sub)))
        complete(upgrade.handleMessages(handler, subprotocol))
      else
        reject(UnsupportedWebSocketSubprotocolRejection(subprotocol.get)) // None.forall == true
    }

  /**
   * Handles WebSocket requests with the given handler by transforming [[Message]] to [[StrictMessage]] within the given `timeout`.
   * Rejects other requests with an [[ExpectedWebSocketRequestRejection]].
   *
   * If the `subprotocol` parameter is None any WebSocket request is accepted. If the `subprotocol` parameter is
   * `Some(protocol)` a WebSocket request is only accepted if the list of subprotocols supported by the client (as
   * announced in the WebSocket request) contains `protocol`. If the client did not offer the protocol in question
   * the request is rejected with an [[UnsupportedWebSocketSubprotocolRejection]] rejection.
   *
   * To support several subprotocols you may chain several `handleWebSocketStrictMessages` routes.
   *
   * @group websocket
   */
  def handleWsMessages(inner: WebSocketResponseBuilder[Message] ⇒ HttpResponse): Route =
    extractActorSystem { implicit system ⇒
      extractUpgradeToWebSocket { upgrade ⇒
        try {
          val response = inner(upgrade.handleWsMessages())
          complete(response)
        } catch {
          case WebSocketUnsupportedSubprotocolException(subprotocol, _, _) ⇒
            reject(UnsupportedWebSocketSubprotocolRejection(subprotocol))
        }
      }
    }
}
