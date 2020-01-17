/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.server

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse }
import akka.http.scaladsl.server.directives.BasicDirectives
import akka.http.scaladsl.settings.{ ParserSettings, RoutingSettings }
import akka.http.scaladsl.util.FastFuture._
import akka.stream.scaladsl.Flow
import akka.stream.{ ActorMaterializerHelper, Materializer }

import scala.concurrent.{ ExecutionContextExecutor, Future }

object Route {

  /**
   * Helper for constructing a Route from a function literal.
   */
  def apply(f: Route): Route = f

  /**
   * "Seals" a route by wrapping it with default exception handling and rejection conversion.
   *
   * A sealed route has these properties:
   *  - The result of the route will always be a complete response, i.e. the result of the future is a
   *    ``Success(RouteResult.Complete(response))``, never a failed future and never a rejected route. These
   *    will be already be handled using the implicitly given [[RejectionHandler]] and [[ExceptionHandler]] (or
   *    the default handlers if none are given or can be found implicitly).
   *  - Consequently, no route alternatives will be tried that were combined with this route
   *    using the ``~`` on routes or the [[Directive.|]] operator on directives.
   */
  def seal(route: Route)(implicit
    routingSettings: RoutingSettings = null,
                         @deprecated("For binary compatibility. parserSettings is never used", since = "10.1.8") parserSettings:ParserSettings = null,
                         rejectionHandler:                                                                                    RejectionHandler = RejectionHandler.default,
                         exceptionHandler:                                                                                    ExceptionHandler = null): Route = {
    import directives.ExecutionDirectives._
    // optimized as this is the root handler for all akka-http applications
    BasicDirectives.extractSettings { theSettings =>
      val effectiveRoutingSettings = if (routingSettings eq null) theSettings else routingSettings

      {
        implicit val routingSettings: RoutingSettings = effectiveRoutingSettings
        (handleExceptions(ExceptionHandler.seal(exceptionHandler)) & handleRejections(rejectionHandler.seal))
          .tapply(_ => route) // execute above directives eagerly, avoiding useless laziness of Directive.addByNameNullaryApply
      }
    }
  }

  /**
   * Turns a `Route` into a server flow.
   *
   * This conversion is also implicitly available whereever a `Route`` is used through [[RouteResult#routeToFlow]].
   */
  // FIXME https://github.com/akka/akka-http/issues/2886 or https://github.com/akka/akka/pull/28494
  def toFlow(route: Route)(implicit system: ActorSystem, materializer: Materializer): Flow[HttpRequest, HttpResponse, NotUsed] =
    Flow[HttpRequest].mapAsync(1)(asyncHandler(route, RoutingSettings(system), ParserSettings(system)))

  /**
   * Turns a `Route` into a server flow.
   */
  @deprecated("replaced by handlerFlow that takes a system", "10.2.0")
  def handlerFlow(route: Route)(implicit
    routingSettings: RoutingSettings,
                                parserSettings:   ParserSettings,
                                materializer:     Materializer,
                                routingLog:       RoutingLog,
                                executionContext: ExecutionContextExecutor = null,
                                rejectionHandler: RejectionHandler         = RejectionHandler.default,
                                exceptionHandler: ExceptionHandler         = null): Flow[HttpRequest, HttpResponse, NotUsed] =
    Flow[HttpRequest].mapAsync(1)(asyncHandler(route))

  // FIXME https://github.com/akka/akka-http/issues/2886 or https://github.com/akka/akka/pull/28494
  def asyncHandler(route: Route, routingSettings: RoutingSettings, parserSettings: ParserSettings)(implicit system: ActorSystem, materializer: Materializer): HttpRequest => Future[HttpResponse] = {
    val routingLog = RoutingLog(system.log)
    // We can seal more efficiently here because we know we can have no inherited settings:
    val sealedRoute = {
      import directives.ExecutionDirectives._
      (handleExceptions(ExceptionHandler.seal(ExceptionHandler.default(routingSettings))(routingSettings)) & handleRejections(RejectionHandler.default.seal))
        .tapply(_ => route)
    }
    request => {
      sealedRoute(new RequestContextImpl(request, routingLog.requestLog(request), routingSettings, parserSettings)(system.dispatcher, materializer)).fast
        .map {
          case RouteResult.Complete(response) => response
          case RouteResult.Rejected(rejected) => throw new IllegalStateException(s"Unhandled rejections '$rejected', unsealed RejectionHandler?!")
        }(materializer.executionContext)
    }
  }

  /**
   * Turns a `Route` into an async handler function.
   */
  @deprecated("replaced by asyncHandler without ec or rejection/exception handlers", "10.2.0")
  def asyncHandler(route: Route)(implicit
    routingSettings: RoutingSettings,
                                 parserSettings:   ParserSettings,
                                 materializer:     Materializer,
                                 routingLog:       RoutingLog,
                                 executionContext: ExecutionContextExecutor = null,
                                 rejectionHandler: RejectionHandler         = RejectionHandler.default,
                                 exceptionHandler: ExceptionHandler         = null): HttpRequest => Future[HttpResponse] = {
    val effectiveEC = if (executionContext ne null) executionContext else materializer.executionContext

    {
      implicit val executionContext: ExecutionContextExecutor = effectiveEC // overrides parameter
      val effectiveParserSettings = if (parserSettings ne null) parserSettings else ParserSettings(ActorMaterializerHelper.downcast(materializer).system)
      val sealedRoute = seal(route)
      request =>
        sealedRoute(new RequestContextImpl(request, routingLog.requestLog(request), routingSettings, effectiveParserSettings)).fast
          .map {
            case RouteResult.Complete(response) => response
            case RouteResult.Rejected(rejected) => throw new IllegalStateException(s"Unhandled rejections '$rejected', unsealed RejectionHandler?!")
          }
    }
  }
}
