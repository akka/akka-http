/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl

import java.util.concurrent.CompletionStage

import akka.actor.{ ActorSystem, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider }
import akka.dispatch.ExecutionContexts.sameThreadExecutionContext
import akka.event.LoggingAdapter
import akka.http.javadsl.model.{ HttpRequest, HttpResponse }
import akka.http.javadsl.settings.ServerSettings
import akka.http.impl.util.JavaMapping.Implicits._
import akka.japi.Function
import akka.stream.Materializer

import scala.compat.java8.FutureConverters._
import scala.concurrent.Future

final class Http2Ext(system: ExtendedActorSystem) extends Extension {
  import language.implicitConversions
  private implicit def javaModelIsScalaModel[J <: AnyRef, S <: J](in: Future[J]): Future[S] = in.asInstanceOf[Future[S]]

  private lazy val delegate = akka.http.scaladsl.Http2(system)

  /**
   * Handle requests using HTTP/2 immediately, without any TLS or negotiation layer.
   *
   * See also http://httpwg.org/specs/rfc7540.html#known-http
   */
  def bindAndHandleRaw(
    handler:      Function[HttpRequest, CompletionStage[HttpResponse]],
    connect:      ConnectHttp,
    materializer: Materializer): CompletionStage[ServerBinding] = {
    delegate.bindAndHandleRaw(handler.apply(_).toScala, connect.host, connect.port)(materializer)
      .map(new ServerBinding(_))(sameThreadExecutionContext).toJava
  }

  /**
   * Handle requests using HTTP/2 immediately, without any TLS or negotiation layer.
   *
   * See also http://httpwg.org/specs/rfc7540.html#known-http
   */
  def bindAndHandleRaw(
    handler:      Function[HttpRequest, CompletionStage[HttpResponse]],
    connect:      ConnectHttp,
    settings:     ServerSettings,
    parallelism:  Int,
    log:          LoggingAdapter,
    materializer: Materializer): CompletionStage[ServerBinding] = {
    delegate.bindAndHandleRaw(
      handler.apply(_).toScala,
      connect.host, connect.port, settings.asScala, parallelism, log)(materializer)
      .map(new ServerBinding(_))(sameThreadExecutionContext).toJava
  }

  def bindAndHandleAsync(
    handler:      Function[HttpRequest, CompletionStage[HttpResponse]],
    connect:      ConnectWithHttps,
    materializer: Materializer): CompletionStage[ServerBinding] = {
    val connectionContext = connect.effectiveConnectionContext(delegate.http.defaultServerHttpContext)
    require(connectionContext.isSecure, "In order to use HTTP/2 you MUST provide an HttpsConnectionContext.")
    delegate.bindAndHandleAsync(
      handler.apply(_).toScala,
      connect.host, connect.port, connectionContext.asScala.asInstanceOf[akka.http.scaladsl.HttpsConnectionContext])(materializer)
      .map(new ServerBinding(_))(sameThreadExecutionContext).toJava
  }

  def bindAndHandleAsync(
    handler:      Function[HttpRequest, CompletionStage[HttpResponse]],
    connect:      ConnectWithHttps,
    settings:     ServerSettings,
    parallelism:  Int,
    log:          LoggingAdapter,
    materializer: Materializer): CompletionStage[ServerBinding] = {
    val connectionContext = connect.effectiveConnectionContext(delegate.http.defaultServerHttpContext)
    require(connectionContext.isSecure, "In order to use HTTP/2 you MUST provide an HttpsConnectionContext.")
    delegate.bindAndHandleAsync(
      handler.apply(_).toScala,
      connect.host, connect.port, connectionContext.asScala.asInstanceOf[akka.http.scaladsl.HttpsConnectionContext], settings.asScala, parallelism, log)(materializer)
      .map(new ServerBinding(_))(sameThreadExecutionContext).toJava
  }
}

object Http2 extends ExtensionId[Http2Ext] with ExtensionIdProvider {
  override def get(system: ActorSystem): Http2Ext = super.get(system)
  def lookup() = Http2
  def createExtension(system: ExtendedActorSystem): Http2Ext = new Http2Ext(system)
}
