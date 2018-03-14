/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl

import java.util.concurrent.CompletionStage

import akka.actor.{ ActorSystem, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider }
import akka.event.LoggingAdapter
import akka.http.javadsl.model.{ HttpRequest, HttpResponse }
import akka.http.javadsl.settings.ServerSettings
import akka.http.impl.util.JavaMapping.Implicits._
import akka.japi.Function
import akka.stream.Materializer

import scala.compat.java8.FutureConverters._
import scala.concurrent.Future

object Http2 extends ExtensionId[Http2] with ExtensionIdProvider {
  override def get(system: ActorSystem): Http2 = super.get(system)
  def lookup() = Http
  def createExtension(system: ExtendedActorSystem): Http2 = new Http2(system)
}

class Http2(system: ExtendedActorSystem) extends Extension {
  import akka.dispatch.ExecutionContexts.{ sameThreadExecutionContext ⇒ ec }

  import language.implicitConversions
  private implicit def javaModelIsScalaModel[J <: AnyRef, S <: J](in: Future[J]): Future[S] = in.asInstanceOf[Future[S]]

  private lazy val delegate = akka.http.scaladsl.Http2(system)

  /**
   * Handle requests using HTTP/2 immediately, without any TLS or negotiation layer.
   */
  def bindAndHandleRaw(
    handler:      Function[HttpRequest, CompletionStage[HttpResponse]],
    connect:      ConnectHttp,
    materializer: Materializer): CompletionStage[ServerBinding] = {
    delegate.bindAndHandleRaw(handler.apply(_).toScala, connect.host, connect.port)(materializer)
      .map(new ServerBinding(_))(ec).toJava
  }

  /**
   * Handle requests using HTTP/2 immediately, without any TLS or negotiation layer.
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
      .map(new ServerBinding(_))(ec).toJava
  }
}
