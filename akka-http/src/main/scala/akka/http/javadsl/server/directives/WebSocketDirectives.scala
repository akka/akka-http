/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.server
package directives

import java.util.{ List ⇒ JList }
import java.util.Optional
import java.util.function.{ Function ⇒ JFunction }

import akka.NotUsed
import akka.http.impl.util.JavaMapping

import scala.collection.JavaConverters._
import akka.http.scaladsl.model.{ ws ⇒ s }
import akka.http.javadsl.model.ws._
import akka.http.javadsl.model.ws.UpgradeToWebSocket
import akka.http.scaladsl.server.{ Directives ⇒ D }
import akka.stream.javadsl.Flow
import akka.stream.{ Materializer, scaladsl }

abstract class WebSocketDirectives extends SecurityDirectives {
  import akka.http.impl.util.JavaMapping.Implicits._

  /**
   * Extract the [[UpgradeToWebSocket]] header if existent. Rejects with an [[ExpectedWebSocketRequestRejection]], otherwise.
   */
  def extractUpgradeToWebSocket(inner: JFunction[UpgradeToWebSocket, Route]): Route = RouteAdapter {
    D.extractUpgradeToWebSocket { header ⇒
      inner.apply(header).delegate
    }
  }

  /**
   * Extract the list of WebSocket subprotocols as offered by the client in the [[Sec-WebSocket-Protocol]] header if
   * this is a WebSocket request. Rejects with an [[ExpectedWebSocketRequestRejection]], otherwise.
   */
  def extractOfferedWsProtocols(inner: JFunction[JList[String], Route]): Route = RouteAdapter {
    D.extractOfferedWsProtocols { list ⇒
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

  /**
   * Handles WebSocket requests with the given handler by transforming [[Message]] to [[StrictMessage]] within the given `timeout` (in milliseconds).
   * Rejects other requests with an [[ExpectedWebSocketRequestRejection]].
   *
   * If the `subprotocol` parameter is None any WebSocket request is accepted. If the `subprotocol` parameter is
   * `Some(protocol)` a WebSocket request is only accepted if the list of subprotocols supported by the client (as
   * announced in the WebSocket request) contains `protocol`. If the client did not offer the protocol in question
   * the request is rejected with an [[UnsupportedWebSocketSubprotocolRejection]] rejection.
   *
   * To support several subprotocols you may chain several `handleWebSocketMessagesForOptionalProtocol` routes.
   */
  def handleWebSocketStrictMessages[T](handler: Flow[StrictMessage, Message, T], materializer: Materializer, timeout: Long, subprotocol: Optional[String]): Route = RouteAdapter {
    import scala.concurrent.duration._
    D.handleWebSocketStrictMessages(adapt(handler), subprotocol.asScala)(materializer, timeout.millis)
  }

  /**
   * Handles WebSocket requests with the given handler for message type [[TextMessage]]. Within the given `timeout` (in milliseconds) transforms any
   * [[TextMessage]] to [[StrictMessage]], so [[TextMessage.isStrict]] will hold true.
   * Fails the `handler` flow if [[BinaryMessage]] is received.
   * Rejects other requests with an [[ExpectedWebSocketRequestRejection]].
   *
   * If the `subprotocol` parameter is None any WebSocket request is accepted. If the `subprotocol` parameter is
   * `Some(protocol)` a WebSocket request is only accepted if the list of subprotocols supported by the client (as
   * announced in the WebSocket request) contains `protocol`. If the client did not offer the protocol in question
   * the request is rejected with an [[UnsupportedWebSocketSubprotocolRejection]] rejection.
   *
   * To support several subprotocols you may chain several `handleWebSocketMessagesForOptionalProtocol` routes.
   */
  def handleWebSocketStrictTextMessages[T](handler: Flow[TextMessage, Message, T], materializer: Materializer, timeout: Long, subprotocol: Optional[String]): Route = RouteAdapter {
    import scala.concurrent.duration._
    D.handleWebSocketStrictTextMessages(adapt(handler), subprotocol.asScala)(materializer, timeout.millis)
  }

  /**
   * Handles WebSocket requests with the given handler for message type [[BinaryMessage]]. Within the given `timeout` (in milliseconds) transforms any
   * [[BinaryMessage]] to [[StrictMessage]], so [[BinaryMessage.isStrict]] will hold true.
   * Fails the `handler` flow if [[TextMessage]] is received.
   * Rejects other requests with an [[ExpectedWebSocketRequestRejection]].
   *
   * If the `subprotocol` parameter is None any WebSocket request is accepted. If the `subprotocol` parameter is
   * `Some(protocol)` a WebSocket request is only accepted if the list of subprotocols supported by the client (as
   * announced in the WebSocket request) contains `protocol`. If the client did not offer the protocol in question
   * the request is rejected with an [[UnsupportedWebSocketSubprotocolRejection]] rejection.
   *
   * To support several subprotocols you may chain several `handleWebSocketMessagesForOptionalProtocol` routes.
   */
  def handleWebSocketStrictBinaryMessages[T](handler: Flow[BinaryMessage, Message, T], materializer: Materializer, timeout: Long, subprotocol: Optional[String]): Route = RouteAdapter {
    import scala.concurrent.duration._
    D.handleWebSocketStrictBinaryMessages(adapt(handler), subprotocol.asScala)(materializer, timeout.millis)
  }

  private def adapt[JM, SM, T](handler: Flow[JM, Message, T])(implicit mapping: JavaMapping[JM, SM]): scaladsl.Flow[SM, s.Message, NotUsed] = {
    scaladsl.Flow[SM].map(_.asJava).via(handler).map(_.asScala)
  }
}
