/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.ws

import akka.stream.javadsl.Flow
import akka.http.javadsl.model._
import akka.http.impl.util.JavaMapping.Implicits._
import akka.japi.Pair
import akka.stream.{ FlowShape, Graph }
import scala.concurrent.Future

object WebSocket {
  /**
   * If a given request is a WebSocket request a response accepting the request is returned using the given handler to
   * handle the WebSocket message stream. If the request wasn't a WebSocket request a response with status code 400 is
   * returned.
   */
  def handleWebSocketRequestWith(request: HttpRequest, handler: Flow[Message, Message, _]): HttpResponse =
    request.asScala.header[UpgradeToWebSocket] match {
      case Some(header) ⇒ header.handleMessagesWith(handler)
      case None         ⇒ HttpResponse.create().withStatus(StatusCodes.BAD_REQUEST).withEntity("Expected WebSocket request")
    }

  /**
   * If a given request is a WebSocket request a response accepting the request is returned using the given handler to
   * handle the WebSocket message stream, as well as a future materialized value. If the request wasn't a WebSocket
   * request a response with status code 400 is returned, as well as a failed future.
   */
  def handleWebSocketRequestWithMat[Mat](
    request:     HttpRequest,
    handler:     Graph[FlowShape[Message, Message], Mat],
    subprotocol: String): Pair[HttpResponse, Future[Mat]] =
    request.asScala.header[UpgradeToWebSocket] match {
      case Some(header) ⇒ header.handleMessagesWithMat(handler, subprotocol)
      case None ⇒ {
        val httpResponse = HttpResponse.create().withStatus(StatusCodes.BAD_REQUEST).withEntity("Expected WebSocket request")
        Pair(httpResponse, Future.failed(new IllegalStateException("Expected WebSocket request")))
      }
    }
}
