/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.server

import akka.NotUsed
import akka.actor.{ ActorSystem, ClassicActorSystemProvider }
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
   * This conversion is also implicitly available whereever a `Route` is used through [[RouteResult#routeToFlow]].
   */
  def toFlow(route: Route)(implicit system: ActorSystem): Flow[HttpRequest, HttpResponse, NotUsed] =
    Flow[HttpRequest].mapAsync(1)(asyncHandler(route, RoutingSettings(system), ParserSettings.forServer(system)))

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

  def asyncHandler(route: Route, routingSettings: RoutingSettings, parserSettings: ParserSettings)(implicit system: ActorSystem): HttpRequest => Future[HttpResponse] = {
    val routingLog = RoutingLog(system.log)

    // This is essentially the same as `seal(route)` but a bit more efficient because we know we have no inherited settings:
    val sealedRoute = {
      import directives.ExecutionDirectives._
      (handleExceptions(ExceptionHandler.default(routingSettings)) & handleRejections(RejectionHandler.default))
        .tapply(_ => route)
    }

    implicit val executionContext = system.dispatcher
    implicit val materializer = Materializer.matFromSystem(new ClassicActorSystemProvider {
      override val classicSystem = system
    })
    createAsyncHandler(sealedRoute, routingLog, routingSettings, parserSettings)
  }

  /**
   * Turns a `Route` into an async handler function.
   */
  @deprecated("Use `asyncHandler` overload without passing ec or rejection/exception handlers. Use directives to specify custom exceptions or rejection handlers", "10.2.0")
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
      createAsyncHandler(seal(route), routingLog, routingSettings, effectiveParserSettings)
    }
  }

  private def createAsyncHandler(sealedRoute: Route, routingLog: RoutingLog, routingSettings: RoutingSettings, parserSettings: ParserSettings)(implicit ec: ExecutionContextExecutor, mat: Materializer): HttpRequest => Future[HttpResponse] = {
    request =>
      sealedRoute(new RequestContextImpl(request, routingLog.requestLog(request), routingSettings, parserSettings)).fast
        .map {
          case RouteResult.Complete(response) => response
          case RouteResult.Rejected(rejected) => throw new IllegalStateException(s"Unhandled rejections '$rejected', unsealed RejectionHandler?!")
        }
  }
}
