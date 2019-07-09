/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.model.ws

import java.util.concurrent.CompletionStage

import akka.Done
import akka.http.impl.util.JavaMapping.Implicits._
import akka.http.javadsl.model._
import akka.stream.{ Materializer, javadsl }
import akka.stream.javadsl.{ CoupledTerminationFlow, Flow, Sink, Source }
import akka.stream.scaladsl.Keep

import scala.compat.java8.FutureConverters._

object WebSocket {
  /**
   * If a given request is a WebSocket request a response accepting the request is returned using the given handler to
   * handle the WebSocket message stream.
   *
   * If you want to build the handler Flow from an independent Source and Sink, consider using one of the provided
   * overloads of this method. If you want to construct the Flow manually, consider using [[CoupledTerminationFlow]]
   * to bind the termination of the both sides of the Flow, as well as the [[WebSocket.ignoreSink]] for the incoming
   * side if wanting to ignore client-side messages, as that Sink also properly handles streaming messages.
   *
   * If the request wasn't a WebSocket request a response with status code 400 is returned.
   */
  def handleWebSocketRequestWith(request: HttpRequest, handler: Flow[Message, Message, _]): HttpResponse =
    request.asScala.header[UpgradeToWebSocket] match {
      case Some(header) => header.handleMessagesWith(handler)
      case None         => HttpResponse.create().withStatus(StatusCodes.BAD_REQUEST).withEntity("Expected WebSocket request")
    }

  /**
   * If a given request is a WebSocket request a response accepting the request is returned using the given source to
   * emit WebSocket messages, and (safely) ignoring any incoming messages.
   *
   * The WebSocket connection will be constructed from a [[WebSocket.ignoreSink]] and the provided source.
   *
   * If the request wasn't a WebSocket request a response with status code 400 is returned.
   */
  def handleWebSocketRequestWithSource(request: HttpRequest, source: Source[Message, _], mat: Materializer): HttpResponse =
    handleWebSocketRequestWith(request, CoupledTerminationFlow.fromSinkAndSource(ignoreSink(mat), source))

  /**
   * If a given request is a WebSocket request a response accepting the request is returned using the given sink to
   * consume the incoming WebSocket messages, and not emiting any messages back to the client.
   *
   * The WebSocket connection will be constructed from a [[WebSocket.ignoreSink]] and the provided source.
   *
   * If the request wasn't a WebSocket request a response with status code 400 is returned.
   */
  def handleWebSocketRequestWithSink(request: HttpRequest, sink: Sink[Message, _]): HttpResponse =
    handleWebSocketRequestWith(request, CoupledTerminationFlow.fromSinkAndSource(sink, Source.maybe[Message]))

  /**
   * When attempting to ignore incoming messages from the client-side on a WebSocket connection,
   * use this Sink instead of `Sink.ignore` since this one will also properly drain incoming `BinaryMessage.Streamed`
   * and `TextMessage.Streamed` messages.
   */
  def ignoreSink(implicit materializer: Materializer): javadsl.Sink[Message, CompletionStage[Done]] =
    Flow.of(classOf[Message])
      .asScala.map(m => m.asScala)
      .toMat(akka.http.scaladsl.model.ws.WebSocket.ignoreSink.mapMaterializedValue(f => f.toJava).asJava)(Keep.right)
      .asJava

}
