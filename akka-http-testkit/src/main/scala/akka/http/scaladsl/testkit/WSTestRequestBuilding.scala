/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.testkit

import akka.http.impl.engine.ws.Handshake.Server
import akka.http.impl.engine.ws.InternalCustomHeader
import akka.http.scaladsl.model.headers.{ Upgrade, UpgradeProtocol, `Sec-WebSocket-Protocol` }
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse, StatusCodes, Uri }
import akka.http.scaladsl.model.ws.{ Message, UpgradeToWebSocket }

import scala.collection.immutable
import akka.stream.{ FlowShape, Graph, Materializer }
import akka.stream.scaladsl.Flow

trait WSTestRequestBuilding {
  def WS(uri: Uri, clientSideHandler: Flow[Message, Message, Any], subprotocols: Seq[String] = Nil)(implicit materializer: Materializer): HttpRequest =
    HttpRequest(uri = uri)
      .addHeader(new InternalCustomHeader("UpgradeToWebSocketTestHeader") with UpgradeToWebSocket {
        def requestedProtocols: immutable.Seq[String] = subprotocols.toList

        def handleMessages(handlerFlow: Graph[FlowShape[Message, Message], Any], subprotocol: Option[String]): HttpResponse = {
          clientSideHandler.join(handlerFlow).run()
          HttpResponse(
            StatusCodes.SwitchingProtocols,
            headers =
              Upgrade(UpgradeProtocol("websocket") :: Nil) :: Server.selectSubProtocol(subprotocols.to[immutable.Seq], subprotocol).toList)
        }
      })
}

object WSTestRequestBuilding extends WSTestRequestBuilding
