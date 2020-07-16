/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.server
package directives

import scala.concurrent.Future
import scala.collection.immutable
import akka.http.scaladsl.marshalling.{ ToEntityMarshaller, ToResponseMarshallable }
import akka.http.scaladsl.model._
import StatusCodes._
import akka.http.scaladsl.util.FastFuture
import akka.http.scaladsl.util.FastFuture._

/**
 * @groupname route Route directives
 * @groupprio route 200
 */
trait RouteDirectives {

  /**
   * Rejects the request with an empty set of rejections.
   *
   * @group route
   */
  def reject: StandardRoute = RouteDirectives._reject

  /**
   * Rejects the request with the given rejections.
   *
   * @group route
   */
  def reject(rejections: Rejection*): StandardRoute =
    StandardRoute(_.reject(rejections: _*))

  /**
   * Completes the request with redirection response of the given type to the given URI.
   *
   * @group route
   */
  def redirect(uri: Uri, redirectionType: Redirection): StandardRoute =
    StandardRoute(_.redirect(uri, redirectionType))

  /**
   * Completes the request using the given arguments.
   *
   * @group route
   */
  def complete(m: => ToResponseMarshallable): StandardRoute =
    StandardRoute(_.complete(m))

  /**
   * Completes the request using the given arguments.
   *
   * @group route
   */
  def complete[T](status: StatusCode, v: => T)(implicit m: ToEntityMarshaller[T]): StandardRoute =
    StandardRoute(_.complete((status, v)))

  /**
   * Completes the request using the given arguments.
   *
   * @group route
   */
  def complete[T](status: StatusCode, headers: immutable.Seq[HttpHeader], v: => T)(implicit m: ToEntityMarshaller[T]): StandardRoute =
    complete((status, headers, v))

  /**
   * Bubbles the given error up the response chain, where it is dealt with by the closest `handleExceptions`
   * directive and its ExceptionHandler.
   *
   * @group route
   */
  def failWith(error: Throwable): StandardRoute =
    StandardRoute(_.fail(error))

  /**
   * Handle the request using a function.
   *
   * @group route
   */
  def handle(handler: HttpRequest => Future[HttpResponse]): StandardRoute =
    { ctx => handler(ctx.request).fast.map(RouteResult.Complete)(ctx.executionContext) }

  /**
   * Handle the request using a function.
   *
   * @group route
   */
  def handleSync(handler: HttpRequest => HttpResponse): StandardRoute =
    { ctx => FastFuture.successful(RouteResult.Complete(handler(ctx.request))) }

  /**
   * Handle the request using an asynchronous partial function.
   *
   * This directive can be used to include external components request processing components defined as PartialFunction
   * (like those provided by akka-grpc) into a routing tree defined as routes.
   *
   * When the partial function is not defined for a request, the request is rejected with an empty list of rejections
   * which is equivalent to a "Not Found" rejection.
   *
   * @group route
   */
  def handle(handler: PartialFunction[HttpRequest, Future[HttpResponse]]): StandardRoute =
    handle(handler, Nil)

  /**
   * Handle the request using an asynchronous partial function.
   *
   * This directive can be used to include external components request processing components defined as PartialFunction
   * (like those provided by akka-grpc) into a routing tree defined as routes.
   *
   * @param rejections The list of rejections to reject with if the handler is not defined for a request.
   *
   * @group route
   */
  def handle(handler: PartialFunction[HttpRequest, Future[HttpResponse]], rejections: Seq[Rejection]): StandardRoute =
    { ctx =>
      handler
        .andThen(_.fast.map(RouteResult.Complete)(ctx.executionContext))
        .applyOrElse[HttpRequest, Future[RouteResult]](ctx.request, _ => ctx.reject(rejections: _*))
    }

  /**
   * Handle the request using a synchronous partial function.
   *
   * This directive can be used to include external components request processing components defined as PartialFunction
   * (like those provided by akka-grpc) into a routing tree defined as routes.
   *
   * When the partial function is not defined for a request, the request is rejected with an empty list of rejections
   * which is equivalent to a "Not Found" rejection.
   *
   * @group route
   */
  def handleSync(handler: PartialFunction[HttpRequest, HttpResponse]): StandardRoute =
    handleSync(handler, Nil)

  /**
   * Handle the request using a synchronous partial function.
   *
   * This directive can be used to include external components request processing components defined as PartialFunction
   * (like those provided by akka-grpc) into a routing tree defined as routes.
   *
   * @param rejections The list of rejections to reject with if the handler is not defined for a request.
   *
   * @group route
   */
  def handleSync(handler: PartialFunction[HttpRequest, HttpResponse], rejections: Seq[Rejection]): StandardRoute =
    { ctx =>
      handler
        .andThen(res => FastFuture.successful(RouteResult.Complete(res)))
        .applyOrElse[HttpRequest, Future[RouteResult]](ctx.request, _ => ctx.reject(rejections: _*))
    }
}

object RouteDirectives extends RouteDirectives {
  private val _reject = StandardRoute(_.reject())
}
