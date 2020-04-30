/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.server

import akka.http.scaladsl.util.FastFuture
import FastFuture._

import scala.concurrent.Future
import scala.runtime.ScalaRunTime

sealed trait InspectableRoute extends Route with Product {
  def name: String
  def children: Seq[Route]

  // FIXME: only there to help intellij
  def apply(ctx: RequestContext): Future[RouteResult]

  override def toString(): String = ScalaRunTime._toString(this)
}
final case class AlternativeRoutes(alternatives: Seq[Route]) extends InspectableRoute {
  def name: String = "concat"
  def children: Seq[Route] = alternatives

  def apply(ctx: RequestContext): Future[RouteResult] = {
    import ctx.executionContext
    def tryNext(remaining: List[Route], rejections: Vector[Rejection]): Future[RouteResult] = remaining match {
      case head :: tail =>
        head(ctx).fast.flatMap {
          case x: RouteResult.Complete             => FastFuture.successful(x)
          case RouteResult.Rejected(newRejections) => tryNext(tail, rejections ++ newRejections)
        }
      case Nil => FastFuture.successful(RouteResult.Rejected(rejections))
    }
    tryNext(alternatives.toList, Vector.empty)
  }
}
sealed trait DirectiveRoute extends InspectableRoute {
  def implementation: Route
  def directiveName: String
  def child: Route

  def apply(ctx: RequestContext): Future[RouteResult] = implementation(ctx)
  def children: Seq[Route] = child :: Nil
  def name: String = s"Directive($directiveName)"
}

object DirectiveRoute {
  def wrap(implementation: Route, child: Route, directiveName: String): DirectiveRoute = implementation match {
    case i: Impl => i.copy(child = child, directiveName = directiveName)
    case x       => Impl(x, child, directiveName)
  }

  implicit def addByNameNullaryApply(directive: Directive0): Route => Route =
    inner => {
      val impl = directive.tapply(_ => inner)
      wrap(impl, inner, directive.metaInformation.fold(s"<unknown (${directive.getClass})>")(_.name))
    }

  // for some reason these seem to take precendence before Directive.addDirectiveApply
  implicit def addDirective1Apply[T](directive: Directive1[T]): (ExtractionToken[T] => Route) => Route =
    { innerCons =>
      val tok = ExtractionToken.create[T]
      val inner = innerCons(tok)
      val real = directive.tapply {
        case Tuple1(t) => ctx =>
          inner(ctx.addTokenValue(tok, t))
      }
      DirectiveRoute.wrap(real, inner, directive.metaInformation.fold("<unknown>")(_.name))
    }
  // TODO: add for more parameters with sbt-boilerplate

  private final case class Impl(
    implementation: Route,
    child:          Route,
    directiveName:  String) extends DirectiveRoute
}

sealed trait ExtractionToken[+T] {
  def value(implicit ctx: ExtractionContext): T = ctx.extract(this)
}
object ExtractionToken {
  // syntax sugar
  implicit def autoExtract[T](token: ExtractionToken[T])(implicit ctx: ExtractionContext): T = ctx.extract(token)

  def create[T]: ExtractionToken[T] = new ExtractionToken[T] {}
}
sealed trait ExtractionContext {
  def extract[T](token: ExtractionToken[T]): T
}
object DynamicDirective {
  def dynamic: (ExtractionContext => Route) => Route =
    inner => ctx => inner {
      new ExtractionContext {
        override def extract[T](token: ExtractionToken[T]): T = ctx.tokenValue(token)
      }
    }(ctx)

  implicit class AddStatic[T](d: Directive1[T]) {
    def static: Directive1[ExtractionToken[T]] =
      Directive { innerCons =>
        val tok = ExtractionToken.create[T]
        val inner = innerCons(Tuple1(tok))
        val real = d { t => ctx =>
          inner(ctx.addTokenValue(tok, t))
        }
        DirectiveRoute.wrap(real, inner, d.metaInformation.fold("<unknown>")(_.name))
      }
  }
}
