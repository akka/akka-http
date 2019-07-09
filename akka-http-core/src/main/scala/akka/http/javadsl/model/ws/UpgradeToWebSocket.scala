/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.model.ws

import java.lang.{ Iterable => JIterable }
import akka.http.scaladsl.{ model => sm }

import akka.annotation.DoNotInherit
import akka.http.javadsl.model._
import akka.stream._
import akka.stream.javadsl.CoupledTerminationFlow

/**
 * A virtual header that WebSocket requests will contain. Use [[UpgradeToWebSocket.handleMessagesWith]] to
 * create a WebSocket handshake response and handle the WebSocket message stream with the given handler.
 */
@DoNotInherit
trait UpgradeToWebSocket extends sm.HttpHeader {
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
   * Convenience API for (safely, including Streamed ones) ignoring all incoming messages from the client-side and
   * emitting messages from the given Source.
   *
   * If you want to build the handler Flow from an independent Source and Sink, consider using one of the provided
   * overloads of this method. If you want to construct the Flow manually, consider using [[CoupledTerminationFlow]]
   * to bind the termination of the both sides of the Flow, as well as the [[WebSocket.ignoreSink]] for the incoming
   * side if wanting to ignore client-side messages, as that Sink also properly handles streaming messages.
   *
   * Returns a response that can be used to answer a WebSocket handshake request. The connection will afterwards
   * use the given handlerFlow to handle WebSocket messages from the client.
   */
  def handleMessagesWithSource(source: Graph[SourceShape[Message], _ <: Any])(implicit mat: Materializer): HttpResponse

  /**
   * Convenience API for (safely, including Streamed ones) ignoring all incoming messages from the client-side and
   * emitting messages from the given Source.
   *
   * Returns a response that can be used to answer a WebSocket handshake request. The connection will afterwards
   * use the given handlerFlow to handle WebSocket messages from the client.
   */
  def handleMessagesWithSink(sink: Graph[SinkShape[Message], _ <: Any]): HttpResponse

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
