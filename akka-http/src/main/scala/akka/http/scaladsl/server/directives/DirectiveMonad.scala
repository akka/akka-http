/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.server.directives

import akka.http.scaladsl.marshalling.ToResponseMarshaller
import akka.http.scaladsl.server.util.Tuple
import akka.http.scaladsl.server.{ Directive, Directive1, Directives, Route }

import scala.language.implicitConversions

// This wrapper exposes `map` and `flatMap` APIs and removes the need for the `Tuple` type class constraint.
// Note that `Directive.tapply[T]()` has type `(T ⇒ Route) ⇒ Route)` (the continuation monad).
// We wrap `Directive.tapply` in this case class and provide the syntax for the `for/yield` block.
// In this way, we avoid having to deal with `Directive1` and `Directive` separately.
case class WrapDirective[+T](tapply: (T ⇒ Route) ⇒ Route) extends AnyVal {
  // Note: this code copies the library code for `Directive.tmap`.
  def map[U](f: T ⇒ U): WrapDirective[U] = WrapDirective { inner ⇒ tapply { values ⇒ inner(f(values)) } }

  // Note: this code copies the library code for `Directive.tflatMap`.
  def flatMap[U](f: T ⇒ WrapDirective[U]): WrapDirective[U] = WrapDirective { inner ⇒ tapply { values ⇒ f(values) tapply inner } }

  // `withFilter` is required when using a pattern-match syntax such as `(s1, s2) ← path(Segment / Segment)`.
  // Scala issue still open: https://issues.scala-lang.org/browse/SI-1336
  // Note: this code copies the library code for `Directive.tfilter`.
  def withFilter(predicate: T ⇒ Boolean): WrapDirective[T] = WrapDirective { inner ⇒
    tapply { values ⇒ ctx ⇒
      if (predicate(values)) {
        inner(values)(ctx)
      } else {
        ctx.reject()
      }
    }
  }
}

private[server] sealed trait AkkaHttpMonadLowPriority {
  // Convert `Directive` to `WrapDirective` to activate new syntax.
  implicit def toWrapped[L](directive: Directive[L]): WrapDirective[L] = WrapDirective(directive.tapply)

  // Convert `WrapDirective` back to `Directive`, so that we could use the library functions.
  implicit def fromWrapped[L: Tuple](wrapped: WrapDirective[L]): Directive[L] = Directive(wrapped.tapply)
}

object DirectiveMonad extends AkkaHttpMonadLowPriority {
  def pure[T](t: T): WrapDirective[T] = WrapDirective { inner ⇒ inner(t) }

  // Unwrap `Tuple1` data when converting Directive1 to WrapDirective..
  implicit def toWrapped1[L](directive1: Directive1[L]): WrapDirective[L] = WrapDirective(lr ⇒ directive1.tapply(t1 ⇒ lr(t1._1)))

  // Convert `WrapDirective` back to `Directive1` when needed.
  implicit def fromWrapped1[L](wrapped: WrapDirective[L]): Directive1[L] = Directive(inner ⇒ wrapped.tapply(t ⇒ inner(Tuple1(t))))

  // Adds a `complete` to enable the syntax: `for { _ <- get } yield "OK"`
  implicit def toRoute[L: ToResponseMarshaller](wrapped: WrapDirective[L]): Route =
    wrapped.tapply(response ⇒ Directives.complete(response))

  // Enable the syntax: `for { _ <- get } yield complete("OK")`
  implicit def toRoute(wrapped: WrapDirective[Route]): Route = wrapped.tapply(identity[Route])

}
