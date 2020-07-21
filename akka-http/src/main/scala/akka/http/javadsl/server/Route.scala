/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.server

import java.util.concurrent.CompletionStage

import akka.NotUsed
import akka.actor.ActorSystem
import akka.actor.ClassicActorSystemProvider
import akka.annotation.{ DoNotInherit, InternalApi }
import akka.http.javadsl.HandlerProvider
import akka.http.javadsl.model.HttpRequest
import akka.http.javadsl.model.HttpResponse
import akka.http.scaladsl
import akka.http.scaladsl.server
import akka.stream.Materializer
import akka.stream.SystemMaterializer
import akka.stream.javadsl.Flow
import akka.japi.function.Function

/**
 * In the Java DSL, a Route can only consist of combinations of the built-in directives. A Route can not be
 * instantiated directly.
 *
 * However, the built-in directives may be combined methods like:
 *
 * <pre>
 * Route myDirective(String test, Supplier<Route> inner) {
 *   return
 *     path("fixed", () ->
 *       path(test),
 *         inner
 *       )
 *     );
 * }
 * </pre>
 *
 * The above example will invoke [inner] whenever the path "fixed/{test}" is matched, where "{test}"
 * is the actual String that was given as method argument.
 */
@DoNotInherit
trait Route extends HandlerProvider {

  /** Converts to the Scala DSL form of an Route. */
  def asScala: server.Route = delegate

  /** INTERNAL API */
  @InternalApi
  private[http] def delegate: scaladsl.server.Route

  def flow(system: ActorSystem, materializer: Materializer): Flow[HttpRequest, HttpResponse, NotUsed]

  def flow(system: ClassicActorSystemProvider): Flow[HttpRequest, HttpResponse, NotUsed] =
    flow(system.classicSystem, SystemMaterializer(system).materializer)

  def function(system: ClassicActorSystemProvider): Function[HttpRequest, CompletionStage[HttpResponse]] = handler(system)
  def handler(system: ClassicActorSystemProvider): Function[HttpRequest, CompletionStage[HttpResponse]]

  /**
   * Seals a route by wrapping it with default exception handling and rejection conversion.
   *
   * A sealed route has these properties:
   *  - The result of the route will always be a complete response, i.e. the result of the future is a
   *    `Success(RouteResult.Complete(response))`, never a failed future and never a rejected route. These
   *    will be already be handled using the default [[RejectionHandler]] and [[ExceptionHandler]].
   *  - Consequently, no route alternatives will be tried that were combined with this route.
   */
  def seal(): Route

  /**
   * Seals a route by wrapping it with explicit exception handling and rejection conversion.
   *
   * A sealed route has these properties:
   *  - The result of the route will always be a complete response, i.e. the result of the future is a
   *    `Success(RouteResult.Complete(response))`, never a failed future and never a rejected route. These
   *    will be already be handled using the given [[RejectionHandler]] and [[ExceptionHandler]].
   *  - Consequently, no route alternatives will be tried that were combined with this route.
   */
  def seal(
    rejectionHandler: RejectionHandler,
    exceptionHandler: ExceptionHandler): Route

  def orElse(alternative: Route): Route
}
