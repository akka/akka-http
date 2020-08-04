/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl

import akka.actor.ClassicActorSystemProvider
import akka.annotation.InternalApi
import akka.event.LoggingAdapter
import akka.http.scaladsl
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse }
import akka.http.scaladsl.settings.ServerSettings
import akka.http.scaladsl.util.FastFuture
import akka.stream.{ Materializer, SystemMaterializer }
import akka.stream.scaladsl.{ Flow, Source }

import scala.concurrent.Future

/**
 * Builder API to create server bindings.
 *
 * Use [[HttpExt.newServerAt()]] to create a builder, use methods to customize settings,
 * and then call one of the bind* methods to bind a server.
 */
trait ServerBuilder {
  /** Change interface to bind to */
  def onInterface(interface: String): ServerBuilder

  /** Change port to bind to */
  def onPort(port: Int): ServerBuilder

  /** Use a custom logger */
  def logTo(log: LoggingAdapter): ServerBuilder

  /**
   * Use custom [[ServerSettings]] for the binding.
   */
  def withSettings(settings: ServerSettings): ServerBuilder

  /**
   * Adapt the current configured settings with a function.
   */
  def adaptSettings(f: ServerSettings => ServerSettings): ServerBuilder

  /**
   * Enable HTTPS for this binding with the given context.
   */
  def enableHttps(context: HttpsConnectionContext): ServerBuilder

  /**
   * Use custom [[Materializer]] for the binding
   */
  def withMaterializer(materializer: Materializer): ServerBuilder

  /**
   * Bind a new HTTP server at the given endpoint and use the given asynchronous `handler`
   * [[akka.stream.scaladsl.Flow]] for processing all incoming connections.
   *
   * The number of concurrently accepted connections can be configured by overriding
   * the `akka.http.server.max-connections` setting. Please see the documentation in the reference.conf for more
   * information about what kind of guarantees to expect.
   *
   * Supports HTTP/2 on the same port if the akka-http2-support module is on the classpath and http2 support is enabled.
   */
  def bind(f: HttpRequest => Future[HttpResponse]): Future[ServerBinding]

  /**
   * Bind a new HTTP server at the given endpoint and uses the given `handler`
   * [[akka.stream.scaladsl.Flow]] for processing all incoming connections.
   *
   * The number of concurrently accepted connections can be configured by overriding
   * the `akka.http.server.max-connections` setting. Please see the documentation in the reference.conf for more
   * information about what kind of guarantees to expect.
   *
   * Supports HTTP/2 on the same port if the akka-http2-support module is on the classpath and http2 support is enabled.
   */
  def bindSync(f: HttpRequest => HttpResponse): Future[ServerBinding]

  /**
   * Binds a new HTTP server at the given endpoint and uses the given `handler`
   * [[akka.stream.scaladsl.Flow]] for processing all incoming connections.
   *
   * The number of concurrently accepted connections can be configured by overriding
   * the `akka.http.server.max-connections` setting. Please see the documentation in the reference.conf for more
   * information about what kind of guarantees to expect.
   */
  def bindFlow(handlerFlow: Flow[HttpRequest, HttpResponse, _]): Future[ServerBinding]

  /**
   * Creates a [[akka.stream.scaladsl.Source]] of [[akka.http.scaladsl.Http.IncomingConnection]] instances which represents a prospective HTTP server binding
   * on the given `endpoint`.
   *
   * Note that each materialization will create a new binding, so
   *
   *  * if the configured  port is 0 the resulting source can be materialized several times. Each materialization will
   * then be assigned a new local port by the operating system, which can then be retrieved by the materialized
   * [[akka.http.scaladsl.Http.ServerBinding]].
   *
   *  * if the configured  port is non-zero subsequent materialization attempts of the produced source will immediately
   * fail, unless the first materialization has already been unbound. Unbinding can be triggered via the materialized
   * [[akka.http.scaladsl.Http.ServerBinding]].
   */
  def connectionSource(): Source[Http.IncomingConnection, Future[ServerBinding]]
}

/**
 * Internal API
 *
 * Use [[HttpExt.newServerAt]] to create a new server builder.
 */
@InternalApi
private[http] object ServerBuilder {
  def apply(interface: String, port: Int, system: ClassicActorSystemProvider): ServerBuilder =
    Impl(
      interface,
      port,
      scaladsl.HttpConnectionContext,
      system.classicSystem.log,
      ServerSettings(system.classicSystem),
      system,
      SystemMaterializer(system).materializer
    )

  private case class Impl(
    interface:    String,
    port:         Int,
    context:      ConnectionContext,
    log:          LoggingAdapter,
    settings:     ServerSettings,
    system:       ClassicActorSystemProvider,
    materializer: Materializer
  ) extends ServerBuilder {
    private val http: scaladsl.HttpExt = scaladsl.Http(system)

    def onInterface(newInterface: String): ServerBuilder = copy(interface = newInterface)
    def onPort(newPort: Int): ServerBuilder = copy(port = newPort)
    def logTo(newLog: LoggingAdapter): ServerBuilder = copy(log = newLog)
    def withSettings(newSettings: ServerSettings): ServerBuilder = copy(settings = newSettings)
    def adaptSettings(f: ServerSettings => ServerSettings): ServerBuilder = copy(settings = f(settings))
    def enableHttps(newContext: HttpsConnectionContext): ServerBuilder = copy(context = newContext)
    def withMaterializer(newMaterializer: Materializer): ServerBuilder = copy(materializer = newMaterializer)

    def connectionSource(): Source[Http.IncomingConnection, Future[ServerBinding]] =
      http.bindImpl(interface, port, context, settings, log)

    def bindFlow(handlerFlow: Flow[HttpRequest, HttpResponse, _]): Future[ServerBinding] =
      http.bindAndHandleImpl(handlerFlow, interface, port, context, settings, log)(materializer)

    def bind(handler: HttpRequest => Future[HttpResponse]): Future[ServerBinding] =
      http.bindAndHandleAsyncImpl(handler, interface, port, context, settings, parallelism = 0, log)(materializer)

    def bindSync(handler: HttpRequest => HttpResponse): Future[ServerBinding] =
      bind(req => FastFuture.successful(handler(req)))
  }
}
