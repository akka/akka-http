# mapRouteResultWith

@@@ div { .group-scala }

## Signature

@@signature [BasicDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala) { #mapRouteResultWith }

@@@

## Description

Changes the message the inner route sends to the responder.

The `mapRouteResult` directive is used as a building block for @ref[Custom Directives](../custom-directives.md) to transform the
@unidoc[RouteResult] coming back from the inner route. It's similar to the @ref[mapRouteResult](mapRouteResult.md) directive but
returning a `CompletionStage` instead of a result immediately, which may be useful for longer running transformations.

See @ref[Result Transformation Directives](index.md#result-transformation-directives) for similar directives.

## Example

Scala
:  @@snip [BasicDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala) { #mapRouteResultWith-0 }

Java
:  @@snip [BasicDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/BasicDirectivesExamplesTest.java) { #mapRouteResultWith }
