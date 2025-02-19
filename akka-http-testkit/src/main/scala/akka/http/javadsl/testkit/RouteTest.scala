/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.testkit

import scala.annotation.varargs
import scala.concurrent.{ ExecutionContextExecutor, Future }
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import akka.actor.ActorSystem
import akka.http.impl.util.JavaMapping.Implicits.AddAsScala
import akka.http.javadsl.model.HttpRequest
import akka.http.javadsl.model.headers.Host
import akka.http.javadsl.server.{ AllDirectives, Directives, Route, RouteResult, RouteResults }
import akka.http.scaladsl.server
import akka.http.scaladsl.server.{ ExceptionHandler, Route => ScalaRoute }
import akka.http.scaladsl.settings.ParserSettings
import akka.http.scaladsl.settings.RoutingSettings
import akka.http.scaladsl.settings.ServerSettings
import akka.http.scaladsl
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.testkit.TestDuration

/**
 * A base class to create route tests for testing libraries. An implementation needs to provide
 * code to provide and shutdown an [[akka.actor.ActorSystem]], [[akka.stream.Materializer]], and [[scala.concurrent.ExecutionContextExecutor]].
 *
 * See `JUnitRouteTest` for an example of a concrete implementation.
 */
abstract class RouteTest extends AllDirectives with WSTestRequestBuilding {
  implicit def system: ActorSystem
  implicit def materializer: Materializer
  implicit def executionContext: ExecutionContextExecutor = system.dispatcher

  protected def defaultAwaitDuration = 3.seconds
  protected def awaitDuration: FiniteDuration = defaultAwaitDuration.dilated

  protected def defaultHostInfo: DefaultHostInfo = DefaultHostInfo(Host.create("example.com"), false)

  def runRoute(route: Route, request: HttpRequest): TestRouteResult =
    runRoute(route, request, defaultHostInfo)

  def runRoute(route: Route, request: HttpRequest, defaultHostInfo: DefaultHostInfo): TestRouteResult =
    runScalaRoute(route.seal().delegate, request, defaultHostInfo)

  def runRouteClientServer(route: Route, request: HttpRequest): TestRouteResult = {
    val response = scaladsl.testkit.RouteTest.runRouteClientServer(request.asScala, route.delegate, ServerSettings(system))
    createTestRouteResultAsync(request, response.map(scalaResponse => RouteResults.complete(scalaResponse)))
  }

  def runRouteUnSealed(route: Route, request: HttpRequest): TestRouteResult =
    runRouteUnSealed(route, request, defaultHostInfo)

  def runRouteUnSealed(route: Route, request: HttpRequest, defaultHostInfo: DefaultHostInfo): TestRouteResult =
    runScalaRoute(route.delegate, request, defaultHostInfo)

  private def runScalaRoute(scalaRoute: ScalaRoute, request: HttpRequest, defaultHostInfo: DefaultHostInfo): TestRouteResult = {
    val effectiveRequest = request.asScala
      .withEffectiveUri(
        securedConnection = defaultHostInfo.isSecuredConnection(),
        defaultHostHeader = defaultHostInfo.getHost().asScala)

    // this will give us the default exception handler
    val sealedExceptionHandler = ExceptionHandler.seal(null)

    val semiSealedRoute = // sealed for exceptions but not for rejections
      akka.http.scaladsl.server.Directives.handleExceptions(sealedExceptionHandler)(scalaRoute)

    val result = semiSealedRoute(new server.RequestContextImpl(effectiveRequest, system.log, RoutingSettings(system), ParserSettings.forServer(system)))
    createTestRouteResultAsync(request, result)
  }

  /**
   * Wraps a list of route alternatives with testing support.
   */
  @varargs
  def testRoute(first: Route, others: Route*): TestRoute =
    new TestRoute {
      val underlying: Route = Directives.concat(first, others: _*)

      def run(request: HttpRequest): TestRouteResult = runRoute(underlying, request)
      def runWithRejections(request: HttpRequest): TestRouteResult = runRouteUnSealed(underlying, request)
      def runClientServer(request: HttpRequest): TestRouteResult = runRouteClientServer(underlying, request)
    }

  protected def createTestRouteResult(request: HttpRequest, result: RouteResult): TestRouteResult =
    createTestRouteResultAsync(request, FastFuture.successful(result))
  protected def createTestRouteResultAsync(request: HttpRequest, result: Future[RouteResult]): TestRouteResult
}
