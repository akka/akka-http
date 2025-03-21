/*
 * Copyright (C) 2020-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl

import akka.NotUsed
import akka.annotation.ApiMayChange

import java.util.concurrent.CompletionStage
import akka.annotation.DoNotInherit
import akka.event.LoggingAdapter
import akka.http.javadsl.model.HttpRequest
import akka.http.javadsl.model.HttpResponse
import akka.http.javadsl.settings.ClientConnectionSettings
import akka.stream.javadsl.Flow

/**
 * Builder for setting up a flow that will create one single connection per materialization to the specified host.
 * When customization is done, the flow is created using [[#http()]], [[#https()]], [[#http2()]] or [[#http2WithPriorKnowledge()]].
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
   * Change with port flows built with this builder connects to, if not set
   * the protocol default is used.
   */
  def toPort(port: Int): OutgoingConnectionBuilder

  /**
   * Create a flow that when materialized creates a single HTTP/1.1 plaintext connection with a default port 80 to the server.
   */
  def http(): Flow[HttpRequest, HttpResponse, CompletionStage[OutgoingConnection]]

  /**
   * Create a flow that when materialized creates a single HTTP/1.1 TLS connection with a default port 443
   */
  def https(): Flow[HttpRequest, HttpResponse, CompletionStage[OutgoingConnection]]

  /**
   * Create a flow that when materialized creates a single HTTP/2 TLS connection with a default port 443
   *
   * Note that the responses are not guaranteed to arrive in the same order as the requests go out (In the case of a HTTP/2 connection)
   * so therefore requests needs to have a [[akka.http.scaladsl.model.RequestResponseAssociation]]
   * which Akka HTTP will carry over to the corresponding response for a request.
   */
  def http2(): Flow[HttpRequest, HttpResponse, CompletionStage[OutgoingConnection]]

  /**
   * Create a flow that when materialized creates a managed HTTP/2 TLS connection with a default port 443.
   *
   * The connection will be re-established as needed.
   *
   * Note that the responses are not guaranteed to arrive in the same order as the requests go out
   * so therefore requests needs to have a [[akka.http.scaladsl.model.RequestResponseAssociation]]
   * which Akka HTTP will carry over to the corresponding response for a request.
   */
  @ApiMayChange
  def managedPersistentHttp2(): Flow[HttpRequest, HttpResponse, NotUsed]

  /**
   * Create a flow that when materialized creates a single HTTP/2 with 'prior knowledge' plaintext connection with a default port 80
   *
   * Note that the responses are not guaranteed to arrive in the same order as the requests go out (In the case of a HTTP/2 connection)
   * so therefore requests needs to have a [[akka.http.scaladsl.model.RequestResponseAssociation]]
   * which Akka HTTP will carry over to the corresponding response for a request.
   */
  def http2WithPriorKnowledge(): Flow[HttpRequest, HttpResponse, CompletionStage[OutgoingConnection]]

  /**
   * Create a flow that when materialized creates a single HTTP/2 with 'prior knowledge' plaintext connection with a default port 80
   *
   * The connection will be re-established as needed.
   *
   * Note that the responses are not guaranteed to arrive in the same order as the requests go out (In the case of a HTTP/2 connection)
   * so therefore requests needs to have a [[akka.http.scaladsl.model.RequestResponseAssociation]]
   * which Akka HTTP will carry over to the corresponding response for a request.
   */
  @ApiMayChange
  def managedPersistentHttp2WithPriorKnowledge(): Flow[HttpRequest, HttpResponse, NotUsed]

  /**
   * Use a custom [[HttpsConnectionContext]] for the connection.
   * Only applicable for `https()` and `http2()`, overrides `defaultHttpsContext`
   */
  def withCustomHttpsConnectionContext(httpsConnectionContext: HttpsConnectionContext): OutgoingConnectionBuilder

  /**
   * Use custom [[ClientConnectionSettings]] for the connection.
   */
  def withClientConnectionSettings(settings: ClientConnectionSettings): OutgoingConnectionBuilder

  /**
   * Use a custom logger
   */
  def logTo(logger: LoggingAdapter): OutgoingConnectionBuilder

}
