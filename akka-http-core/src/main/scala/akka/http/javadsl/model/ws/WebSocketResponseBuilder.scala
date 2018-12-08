/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.model.ws

import akka.actor.ActorSystem
import akka.http.javadsl.model.HttpResponse
import akka.stream.{ FlowShape, Graph }
import akka.http.impl.util.JavaMapping
import akka.http.scaladsl.model.{ ws ⇒ s }

import scala.concurrent.duration._

private[http] class WebSocketResponseBuilder private (
  private val upgrade:     s.UpgradeToWebSocket,
  private val timeout:     Option[FiniteDuration],
  private val maxBytes:    Option[Long],
  private val subprotocol: Option[String])(implicit _system: ActorSystem) {

  def handleTextMessagesWith(flow: Graph[FlowShape[TextMessage, Message], _ <: Any]): HttpResponse =
    scalaBuilder.only[s.TextMessage].handleWith(JavaMapping.toScala(flow))

  def handleBinaryMessagesWith(flow: Graph[FlowShape[BinaryMessage, Message], _ <: Any]): HttpResponse =
    scalaBuilder.only[s.BinaryMessage].handleWith(JavaMapping.toScala(flow))

  def handleWith(flow: Graph[FlowShape[StrictMessage, Message], _ <: Any]): HttpResponse =
    scalaBuilder.handleWith(JavaMapping.toScala(flow))

  def forProtocol(protocol: String): WebSocketResponseBuilder =
    copy(subprotocol = Some(protocol))

  def toStrictTimeout(strictTimeoutInMillis: Long): WebSocketResponseBuilder =
    copy(timeout = Some(strictTimeoutInMillis.milliseconds))

  def maxStrictSize(bytes: Long): WebSocketResponseBuilder =
    copy(maxBytes = Some(bytes))

  private def copy(
    upgrade:     s.UpgradeToWebSocket   = upgrade,
    timeout:     Option[FiniteDuration] = timeout,
    maxBytes:    Option[Long]           = maxBytes,
    subprotocol: Option[String]         = subprotocol): WebSocketResponseBuilder =
    new WebSocketResponseBuilder(upgrade, timeout, maxBytes, subprotocol)

  private def scalaBuilder = s.WebSocketResponseBuilder[s.Message](upgrade, timeout, maxBytes, subprotocol)
}

private[http] object WebSocketResponseBuilder {
  def apply(upgrade: s.UpgradeToWebSocket)(implicit system: ActorSystem): WebSocketResponseBuilder =
    new WebSocketResponseBuilder(upgrade, timeout = None, maxBytes = None, subprotocol = None)
}
