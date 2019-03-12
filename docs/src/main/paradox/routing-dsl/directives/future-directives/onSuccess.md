# onSuccess

@@@ div { .group-scala }

## Signature

```scala
def onSuccess(unitFuture: Future[Unit]): Directive0
def onSuccess(simpleTypeFuture: Future[T]): Directive1[T]
def onSuccess(hlistFuture: Future[T_0 :: ... T_i ... :: HNil]): Directive[T_0 :: ... T_i ... :: HNil]
```

The signature shown is simplified and written in pseudo-syntax, the real signature uses magnets. <a id="^1" href="#1">[1]</a>.

> <a id="1" href="#^1">[1]</a> See [The Magnet Pattern](http://spray.io/blog/2012-12-13-the-magnet-pattern/) for an explanation of magnet-based overloading.

@@@

## Description

Evaluates its parameter of type @scala[`Future[T]`]@java[`CompletionStage<T>`], and once it has been completed successfully,
extracts its result as a value of type `T` and passes it to the inner route.

If the future fails its failure throwable is bubbled up to the nearest @unidoc[ExceptionHandler].

To handle the `Failure` case manually as well, use @ref[onComplete](onComplete.md), instead.

## Example

Scala
:  @@snip [FutureDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/FutureDirectivesExamplesSpec.scala) { #onSuccess }

Java
:  @@snip [FutureDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/FutureDirectivesExamplesTest.java) { #onSuccess }
