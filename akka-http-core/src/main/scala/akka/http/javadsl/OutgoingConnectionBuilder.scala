/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl

import java.util.concurrent.CompletionStage

import akka.annotation.DoNotInherit
import akka.event.LoggingAdapter
import akka.http.javadsl.model.HttpRequest
import akka.http.javadsl.model.HttpResponse
import akka.http.javadsl.settings.ClientConnectionSettings
import akka.stream.javadsl.Flow

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
   * Create flow that when materialized creates a single connection to the HTTP server.
   *
   * The responses are not guaranteed to arrive in the same order as the requests go out
   * so therefore requests needs to have a [[akka.http.scaladsl.model.RequestResponseAssociation]]
   * which Akka HTTP will carry over to the corresponding response for a request.
   */
  def connectionFlow(): Flow[HttpRequest, HttpResponse, CompletionStage[OutgoingConnection]]
}
