/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.testkit

import akka.http.impl.engine.server.InternalCustomHeader
import akka.http.scaladsl.model.headers.{ UpgradeProtocol, Upgrade, `Sec-WebSocket-Protocol` }
import akka.http.scaladsl.model.{ StatusCodes, HttpResponse, HttpRequest, Uri }
import akka.http.scaladsl.model.ws.{ UpgradeToWebSocket, WebSocketUpgrade, Message }
import akka.http.scaladsl.model.AttributeKeys.webSocketUpgrade
import scala.collection.immutable
import akka.stream.{ Materializer, Graph, FlowShape }
import akka.stream.scaladsl.Flow

trait WSTestRequestBuilding {
  def WS(uri: Uri, clientSideHandler: Flow[Message, Message, Any], subprotocols: Seq[String] = Nil)(implicit materializer: Materializer): HttpRequest = {
    val upgrade = new InternalCustomHeader("UpgradeToWebSocketTestHeader") with UpgradeToWebSocket with WebSocketUpgrade {
      def requestedProtocols: immutable.Seq[String] = subprotocols.toList

      def handleMessages(handlerFlow: Graph[FlowShape[Message, Message], Any], subprotocol: Option[String]): HttpResponse = {
        clientSideHandler.join(handlerFlow).run()
        HttpResponse(
          StatusCodes.SwitchingProtocols,
          headers =
            Upgrade(UpgradeProtocol("websocket") :: Nil) ::
              subprotocol.map(p => `Sec-WebSocket-Protocol`(p :: Nil)).toList)
      }
    }
    HttpRequest(uri = uri)
      .addAttribute(webSocketUpgrade, upgrade)
      .addHeader(upgrade)
  }
}

object WSTestRequestBuilding extends WSTestRequestBuilding
