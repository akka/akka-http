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
   * Switch to non TLS and port 80 from default 443 and TLS enabled.
   */
  def unsecure(): OutgoingConnectionBuilder

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
   * so therefore requests needs to have a [[akka.http.scaladsl.model.http2.RequestResponseAssociation]]
   * which Akka HTTP will carry over to the corresponding response for a request.
   */
  def unorderedFlow(): Flow[HttpRequest, HttpResponse, CompletionStage[OutgoingConnection]]
}
