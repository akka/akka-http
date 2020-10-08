/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl

import java.net.InetAddress

import akka.annotation.DoNotInherit
import akka.event.LoggingAdapter
import akka.http.javadsl.settings.ClientConnectionSettings
import akka.http.scaladsl.Http.OutgoingConnection
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse }
import akka.stream.scaladsl.Flow

import scala.concurrent.Future

/**
 * Not for user extension
 */
@DoNotInherit
trait SingleConnectionBuilder {
  /**
   * Change which host flows built with this builder connects to
   */
  def toHost(host: String): SingleConnectionBuilder

  /**
   * Change with port flows built with this builder connects to
   */
  def toPort(port: Int): SingleConnectionBuilder

  def enableHttps(context: HttpsConnectionContext): SingleConnectionBuilder

  /**
   * Use a specific local host and port to create the outgoing connection from
   */
  def withLocalAddress(localAddress: InetAddress): SingleConnectionBuilder

  /**
   * Use a custom [[ConnectionContext]] for the connection.
   */
  def withConnectionContext(context: ConnectionContext): SingleConnectionBuilder

  /**
   * Use custom [[ClientConnectionSettings]] for the connection.
   */
  def withClientConnectionSettings(settings: ClientConnectionSettings): SingleConnectionBuilder

  /**
   * Use a custom logger
   */
  def logTo(logger: LoggingAdapter): SingleConnectionBuilder

  /**
   * Create flow that when materialized creates a single connection to the HTTP server.
   *
   * Note that the responses are not guaranteed to arrive in the same order as the requests go out (In the case of a HTTP/2 server)
   * so therefore requests needs to have a [[akka.http.scaladsl.model.http2.RequestResponseAssociation]]
   * which Akka HTTP will carry over to the corresponding response for a request.
   */
  def connectionFlow(): Flow[HttpRequest, HttpResponse, Future[OutgoingConnection]]
}
