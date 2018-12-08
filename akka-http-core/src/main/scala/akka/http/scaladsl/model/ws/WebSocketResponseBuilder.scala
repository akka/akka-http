/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.model.ws

import akka.actor.ActorSystem
import akka.http.scaladsl.model.HttpResponse
import akka.http.impl.util._
import akka.stream.{ FlowShape, Graph, scaladsl }
import WebSocketResponseBuilder._

import scala.collection.immutable.Seq
import scala.concurrent.duration._

/**
 * Used to build a [[HttpResponse]] to handle websocket requests. Converts all the messages coming from request into their strict forms ([[Message.Strict]]
 * within a configurable timeout and makes sure a message size cannot exceed the configurable maximum bytes value.
 *
 * It is possible to define subprotocol and accepted [[Message]] subtype in addition to timeout and maximum bytes.
 */
private[http] case class WebSocketResponseBuilder[M <: Message: ToStrictFlow] private[http] (
  private val upgrade:     UpgradeToWebSocket,
  private val timeout:     Option[FiniteDuration],
  private val maxBytes:    Option[Long],
  private val subprotocol: Option[String])(implicit _system: ActorSystem) {

  def handleWith(flow: Graph[FlowShape[M#Strict, Message], Any]): HttpResponse = {
    val handlerFlow = ToStrictFlow[M].flow(timeout.getOrElse(defaultTimeout), maxBytes.getOrElse(defaultMaxBytes)).via(flow)
    try upgrade.handleMessages(handlerFlow, subprotocol) catch {
      // checks if IllegalArgumentException is thrown for invalid subprotocol by UpgradeToWebSocket#handleMessages
      case e: IllegalArgumentException if subprotocol.isDefined ⇒
        throw WebSocketUnsupportedSubprotocolException(subprotocol.get, upgrade.requestedProtocols, e.getMessage)
    }
  }

  def only[T <: Message: ToStrictFlow]: WebSocketResponseBuilder[T] = new WebSocketResponseBuilder[T](upgrade, timeout, maxBytes, subprotocol)

  def forProtocol(protocol: String): WebSocketResponseBuilder[M] = copy(subprotocol = Some(protocol))

  def toStrictTimeout(strictTimeout: FiniteDuration): WebSocketResponseBuilder[M] = copy(timeout = Some(strictTimeout))

  def maxStrictSize(bytes: Long): WebSocketResponseBuilder[M] = copy(maxBytes = Some(bytes))

  private def defaultTimeout: FiniteDuration = _system.settings.config.getFiniteDuration("akka.http.server.websocket.to-strict-timeout")
  private def defaultMaxBytes: Long = _system.settings.config.getPossiblyInfiniteBytes("akka.http.server.websocket.max-to-strict-bytes")
}

private[http] object WebSocketResponseBuilder {
  private[scaladsl] def apply(upgrade: UpgradeToWebSocket)(implicit system: ActorSystem): WebSocketResponseBuilder[Message] =
    WebSocketResponseBuilder[Message](upgrade, timeout = None, maxBytes = None, subprotocol = None)

  sealed trait ToStrictFlow[M <: Message] {
    def flow(timeout: FiniteDuration, maxBytes: Long): scaladsl.Flow[Message, M#Strict, Any]
  }

  object ToStrictFlow {
    private[ws] def apply[M <: Message](implicit toStrictFlow: ToStrictFlow[M]) = toStrictFlow

    implicit val textToStrict: ToStrictFlow[TextMessage] = new ToStrictFlow[TextMessage] {
      override def flow(timeout: FiniteDuration, maxBytes: Long): scaladsl.Flow[Message, TextMessage.Strict, Any] =
        scaladsl.Flow[Message].map {
          case textMessage: TextMessage ⇒ textMessage
          case _                        ⇒ throw new RuntimeException("Received BinaryMessage while expecting TextMessage.")
        }.flatMapConcat(textMessage ⇒ textMessage.toStrict(timeout, maxBytes))
    }

    implicit val binaryToStrict: ToStrictFlow[BinaryMessage] = new ToStrictFlow[BinaryMessage] {
      override def flow(timeout: FiniteDuration, maxBytes: Long): scaladsl.Flow[Message, BinaryMessage.Strict, Any] =
        scaladsl.Flow[Message].map {
          case binaryMessage: BinaryMessage ⇒ binaryMessage
          case _                            ⇒ throw new RuntimeException("Received TextMessage while expecting BinaryMessage.")
        }.flatMapConcat(binaryMessage ⇒ binaryMessage.toStrict(timeout, maxBytes))
    }

    implicit val messageToStrict: ToStrictFlow[Message] = new ToStrictFlow[Message] {
      override def flow(timeout: FiniteDuration, maxBytes: Long): scaladsl.Flow[Message, Message#Strict, Any] =
        scaladsl.Flow[Message].flatMapConcat(message ⇒ message.toStrict(timeout, maxBytes))
    }
  }
}

/**
 * Thrown when subprotocol is not in the list of client requested protocols.
 *
 * The exception ([[IllegalArgumentException]]) and the message are same
 * to what [[akka.http.scaladsl.model.ws.UpgradeToWebSocket#handleMessages()]] throws in the same case.
 */
final case class WebSocketUnsupportedSubprotocolException(subprotocol: String, requestedProtocols: Seq[String], message: String)
  extends IllegalArgumentException(message)
