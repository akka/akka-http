/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.server
package directives

import java.util.{ List => JList }
import java.util.Optional
import java.util.function.{ Function => JFunction }

import akka.NotUsed
import akka.http.scaladsl.model.{ ws => s }
import akka.http.javadsl.model.ws.Message
import akka.http.javadsl.model.ws.UpgradeToWebSocket
import akka.http.javadsl.model.ws.WebSocketUpgrade
import akka.http.scaladsl.server.{ Directives => D }
import akka.stream.javadsl.Flow
import akka.stream.scaladsl

abstract class WebSocketDirectives extends SecurityDirectives {
  import akka.http.impl.util.JavaMapping.Implicits._

  /**
   * Extract the [[UpgradeToWebSocket]] header if existent. Rejects with an [[ExpectedWebSocketRequestRejection]], otherwise.
   *
   * @deprecated use `webSocketUpgrade` instead since 10.2.0
   */
  @Deprecated
  @deprecated("use `extractWebSocketUpgrade` instead", since = "10.2.0")
  def extractUpgradeToWebSocket(inner: JFunction[UpgradeToWebSocket, Route]): Route = RouteAdapter {
    D.extractUpgradeToWebSocket { header =>
      inner.apply(header).delegate
    }
  }

  /**
   * Extract the UpgradeToWebSocket attribute if this is a WebSocket request.
   * Rejects with an [[ExpectedWebSocketRequestRejection]], otherwise.
   */
  def extractWebSocketUpgrade(inner: JFunction[WebSocketUpgrade, Route]): Route = RouteAdapter {
    D.extractWebSocketUpgrade { header =>
      inner.apply(header).delegate
    }
  }

  /**
   * Extract the list of WebSocket subprotocols as offered by the client in the [[Sec-WebSocket-Protocol]] header if
   * this is a WebSocket request. Rejects with an [[ExpectedWebSocketRequestRejection]], otherwise.
   */
  def extractOfferedWsProtocols(inner: JFunction[JList[String], Route]): Route = RouteAdapter {
    import scala.jdk.CollectionConverters._
    D.extractOfferedWsProtocols { (list: Seq[String]) =>
      inner.apply(list.asJava).delegate
    }
  }

  /**
   * Handles WebSocket requests with the given handler and rejects other requests with an
   * [[ExpectedWebSocketRequestRejection]].
   */
  def handleWebSocketMessages[T](handler: Flow[Message, Message, T]): Route = RouteAdapter {
    D.handleWebSocketMessages(adapt(handler))
  }

  /**
   * Handles WebSocket requests with the given handler if the given subprotocol is offered in the request and
   * rejects other requests with an [[ExpectedWebSocketRequestRejection]] or an [[UnsupportedWebSocketSubprotocolRejection]].
   */
  def handleWebSocketMessagesForProtocol[T](handler: Flow[Message, Message, T], subprotocol: String): Route = RouteAdapter {
    D.handleWebSocketMessagesForProtocol(adapt(handler), subprotocol)
  }

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
   */
  def handleWebSocketMessagesForOptionalProtocol[T](handler: Flow[Message, Message, T], subprotocol: Optional[String]): Route = RouteAdapter {
    D.handleWebSocketMessagesForOptionalProtocol(adapt(handler), subprotocol.asScala)
  }

  private def adapt[T](handler: Flow[Message, Message, T]): scaladsl.Flow[s.Message, s.Message, NotUsed] = {
    scaladsl.Flow[s.Message].map(_.asJava).via(handler).map(_.asScala)
  }
}
