/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl

import java.net.InetAddress

import akka.actor.ClassicActorSystemProvider
import akka.annotation.DoNotInherit
import akka.annotation.InternalApi
import akka.event.LoggingAdapter
import akka.http.scaladsl.Http.OutgoingConnection
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse }
import akka.http.scaladsl.settings.ClientConnectionSettings
import akka.stream.scaladsl.Flow

import scala.concurrent.Future

/**
 * Builder for setting up a flow that will create one single connection per materialization to the specified host.
 * When customization is done, the flow is created using [[#connectionFlow()]].
 *
 * Not for user extension
 */
@DoNotInherit
trait OutgoingConnectionBuilder {
  /**
   * Change which host flows built with this builder connects to
   */
  def toHost(host: String): OutgoingConnectionBuilder

  /**
   * Change with port flows built with this builder connects to
   */
  def toPort(port: Int): OutgoingConnectionBuilder

  def enableHttps(context: HttpsConnectionContext): OutgoingConnectionBuilder

  /**
   * Use a specific local host and port to create the outgoing connection from
   */
  def withLocalAddress(localAddress: InetAddress): OutgoingConnectionBuilder

  /**
   * Use a custom [[ConnectionContext]] for the connection.
   */
  def withConnectionContext(context: ConnectionContext): OutgoingConnectionBuilder

  /**
   * Use custom [[ClientConnectionSettings]] for the connection.
   */
  def withClientConnectionSettings(settings: ClientConnectionSettings): OutgoingConnectionBuilder

  /**
   * Use a custom logger
   */
  def logTo(logger: LoggingAdapter): OutgoingConnectionBuilder

  /**
   * Create flow that when materialized creates a single connection to the HTTP server.
   *
   * Note that the responses are not guaranteed to arrive in the same order as the requests go out (In the case of a HTTP/2 server)
   * so therefore requests needs to have a [[akka.http.scaladsl.model.http2.RequestResponseAssociation]]
   * which Akka HTTP will carry over to the corresponding response for a request.
   */
  def connectionFlow(): Flow[HttpRequest, HttpResponse, Future[OutgoingConnection]]
}

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object OutgoingConnectionBuilder {

  def apply(host: String, port: Int, system: ClassicActorSystemProvider): OutgoingConnectionBuilder =
    Impl(
      host,
      port,
      clientConnectionSettings = ClientConnectionSettings(system),
      connectionContext = ConnectionContext.noEncryption(),
      log = system.classicSystem.log,
      localAddress = None
    )

  private case class Impl(
    host:                     String,
    port:                     Int,
    clientConnectionSettings: ClientConnectionSettings,
    connectionContext:        ConnectionContext,
    log:                      LoggingAdapter,
    localAddress:             Option[InetAddress]) extends OutgoingConnectionBuilder {

    override def toHost(host: String): OutgoingConnectionBuilder = copy(host = host)

    override def toPort(port: Int): OutgoingConnectionBuilder = copy(port = port)

    override def enableHttps(context: HttpsConnectionContext): OutgoingConnectionBuilder = copy(connectionContext = context)

    override def withLocalAddress(localAddress: InetAddress): OutgoingConnectionBuilder = copy(localAddress = Some(localAddress))

    override def withConnectionContext(context: ConnectionContext): OutgoingConnectionBuilder = copy(connectionContext = context)

    override def withClientConnectionSettings(settings: ClientConnectionSettings): OutgoingConnectionBuilder = copy(clientConnectionSettings = settings)

    override def logTo(logger: LoggingAdapter): OutgoingConnectionBuilder = copy(log = logger)

    override def connectionFlow(): Flow[HttpRequest, HttpResponse, Future[OutgoingConnection]] = ???
  }
}
