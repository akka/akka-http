/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.server

import akka.NotUsed
import akka.actor.ClassicActorSystemProvider
import akka.http.javadsl
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse }
import akka.http.scaladsl.settings.{ ParserSettings, RoutingSettings }
import akka.stream.Materializer
import akka.stream.scaladsl.Flow

import scala.collection.JavaConverters._
import scala.collection.immutable
import scala.concurrent.{ ExecutionContext, ExecutionContextExecutor }

/**
 * The result of handling a request.
 *
 * As a user you typically don't create RouteResult instances directly.
 * Instead, use the methods on the [[RequestContext]] to achieve the desired effect.
 */
sealed trait RouteResult extends javadsl.server.RouteResult

object RouteResult {
  final case class Complete(response: HttpResponse) extends javadsl.server.Complete with RouteResult {
    override def getResponse = response
  }
  final case class Rejected(rejections: immutable.Seq[Rejection]) extends javadsl.server.Rejected with RouteResult {
    override def getRejections = rejections.map(r => r: javadsl.server.Rejection).asJava
  }

  /**
   * Turns a `Route` into a server flow.
   *
   * This implicit conversion is defined here because `Route` is an alias for
   * `RequestContext => Future[RouteResult]`, and the fact that `RouteResult`
   * is in that type means this implicit conversion come into scope whereever
   * a `Route` is given but a `Flow` is expected.
   */
  implicit def routeToFlow(route: Route)(implicit system: ClassicActorSystemProvider): Flow[HttpRequest, HttpResponse, NotUsed] =
    Route.toFlow(route)

  @deprecated("Replaced by routeToFlow", "10.2.0")
  def route2HandlerFlow(route: Route)(
    implicit
    routingSettings:  RoutingSettings,
    parserSettings:   ParserSettings,
    materializer:     Materializer,
    routingLog:       RoutingLog,
    executionContext: ExecutionContext = null,
    rejectionHandler: RejectionHandler = RejectionHandler.default,
    exceptionHandler: ExceptionHandler = null
  ): Flow[HttpRequest, HttpResponse, NotUsed] = {
    implicit val ec: ExecutionContextExecutor = executionContext match {
      case e: ExecutionContextExecutor => e
      case _                           => null
    }
    Route.handlerFlow(route)(routingSettings, parserSettings, materializer, routingLog, ec, rejectionHandler, exceptionHandler)
  }
}
