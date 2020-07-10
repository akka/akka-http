/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.model.ws

import java.lang.{ Iterable => JIterable }

import akka.http.javadsl.model.HttpResponse
import akka.stream.{ FlowShape, Graph, SinkShape, SourceShape }

/**
 * An attribute that WebSocket requests will contain. Use [[WebSocketUpgrade.handleMessagesWith]] to
 * create a WebSocket handshake response and handle the WebSocket message stream with the given handler.
 *
 * This is a low-level API. You might want to use `handleWebSocketMessages` instead as documented
 * at https://doc.akka.io/docs/akka-http/current/server-side/websocket-support.html#routing-support
 */
trait WebSocketUpgrade {
  /**
   * Returns the sequence of protocols the client accepts.
   *
   * See http://tools.ietf.org/html/rfc6455#section-1.9
   */
  def getRequestedProtocols(): JIterable[String]

  /**
   * Returns a response that can be used to answer a WebSocket handshake request. The connection will afterwards
   * use the given handlerFlow to handle WebSocket messages from the client.
   */
  def handleMessagesWith(handlerFlow: Graph[FlowShape[Message, Message], _ <: Any]): HttpResponse

  /**
   * Returns a response that can be used to answer a WebSocket handshake request. The connection will afterwards
   * use the given handlerFlow to handle WebSocket messages from the client. The given subprotocol must be one
   * of the ones offered by the client.
   */
  def handleMessagesWith(handlerFlow: Graph[FlowShape[Message, Message], _ <: Any], subprotocol: String): HttpResponse

  /**
   * Returns a response that can be used to answer a WebSocket handshake request. The connection will afterwards
   * use the given inSink to handle WebSocket messages from the client and the given outSource to send messages to the client.
   */
  def handleMessagesWith(inSink: Graph[SinkShape[Message], _ <: Any], outSource: Graph[SourceShape[Message], _ <: Any]): HttpResponse

  /**
   * Returns a response that can be used to answer a WebSocket handshake request. The connection will afterwards
   * use the given inSink to handle WebSocket messages from the client and the given outSource to send messages to the client.
   *
   * The given subprotocol must be one of the ones offered by the client.
   */
  def handleMessagesWith(inSink: Graph[SinkShape[Message], _ <: Any], outSource: Graph[SourceShape[Message], _ <: Any], subprotocol: String): HttpResponse
}
