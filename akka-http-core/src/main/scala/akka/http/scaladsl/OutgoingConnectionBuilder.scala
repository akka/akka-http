/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl

import akka.annotation.DoNotInherit
import akka.annotation.InternalApi
import akka.event.LoggingAdapter
import akka.http.scaladsl.Http.OutgoingConnection
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.settings.ClientConnectionSettings
import akka.stream.scaladsl.Flow
import akka.http.javadsl.{ OutgoingConnectionBuilder => JOutgoingConnectionBuilder }

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

  /**
   * Switch to HTTP/2 over TLS on port 443
   */
  def http2(): OutgoingConnectionBuilder

  /**
   * Switch to HTTP/1.1 over TLS on port 443
   */
  def https1(): OutgoingConnectionBuilder

  /**
   * Switch to HTTP/1.1 over a plaintext connection on port 80
   */
  def insecureHttp1(): OutgoingConnectionBuilder

  /**
   * Switch to HTTP/2 with 'prior knowledge' over a plaintext connection on port 80
   */
  def insecureForcedHttp2(): OutgoingConnectionBuilder

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
   * INTERNAL API
   */
  @InternalApi
  private[akka] def toJava: JOutgoingConnectionBuilder

  /**
   * Create flow that when materialized creates a single connection to the HTTP server.
   *
   * Note that the responses are not guaranteed to arrive in the same order as the requests go out (In the case of a HTTP/2 connection)
   * so therefore requests needs to have a [[akka.http.scaladsl.model.RequestResponseAssociation]]
   * which Akka HTTP will carry over to the corresponding response for a request.
   */
  def connectionFlow(): Flow[HttpRequest, HttpResponse, Future[OutgoingConnection]]
}
