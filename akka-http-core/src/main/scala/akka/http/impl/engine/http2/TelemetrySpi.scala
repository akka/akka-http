/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.NotUsed
import akka.actor.ActorSystem
import akka.actor.ExtendedActorSystem
import akka.annotation.InternalApi
import akka.annotation.InternalStableApi
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.HttpResponse
import akka.stream.Attributes
import akka.stream.Attributes.Attribute
import akka.stream.scaladsl.BidiFlow
import akka.stream.scaladsl.Tcp

import java.net.InetSocketAddress

/**
 * INTERNAL API
 */
@InternalApi
private[http] object TelemetrySpi {
  private val ConfigKey = "akka.http.http2-telemetry-class"
  def create(system: ActorSystem): TelemetrySpi = {
    if (!system.settings.config.hasPath(ConfigKey)) NoOpTelemetry
    else {
      val fqcn = system.settings.config.getString(ConfigKey)
      system.asInstanceOf[ExtendedActorSystem].dynamicAccess
        .createInstanceFor[TelemetrySpi](fqcn, (classOf[ActorSystem], system) :: Nil)
        .get
    }
  }
}

/**
 * INTERNAL API
 */
@InternalApi
object TelemetryAttributes {
  final case class ConnectionMeta(local: Option[InetSocketAddress], val remote: InetSocketAddress) extends Attribute
  def prepareServerFlowAttributes(incomingConnection: Tcp.IncomingConnection): Attributes =
    Attributes(ConnectionMeta(Some(incomingConnection.localAddress), incomingConnection.remoteAddress))
  def prepareClientFlowAttributes(serverHost: String, serverPort: Int): Attributes =
    Attributes(ConnectionMeta(None, InetSocketAddress.createUnresolved(serverHost, serverPort)))
}

/**
 * INTERNAL API
 */
@InternalStableApi
trait TelemetrySpi {
  /**
   * Flow to intercept server connections. When run the flow will have the ConnectionMeta attribute set.
   */
  def client: BidiFlow[HttpRequest, HttpRequest, HttpResponse, HttpResponse, NotUsed]
  /**
   * Flow to intercept server connections. When run the flow will have the ConnectionMeta attribute set.
   */
  def server: BidiFlow[HttpResponse, HttpResponse, HttpRequest, HttpRequest, NotUsed]
}

/**
 * INTERNAL API
 */
@InternalApi
private[http] object NoOpTelemetry extends TelemetrySpi {
  override def client: BidiFlow[HttpRequest, HttpRequest, HttpResponse, HttpResponse, NotUsed] = BidiFlow.identity
  override def server: BidiFlow[HttpResponse, HttpResponse, HttpRequest, HttpRequest, NotUsed] = BidiFlow.identity
}

