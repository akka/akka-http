<a id="mapinnerroute"></a>
# mapInnerRoute

## Signature

FIXME@@snip [BasicDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala) { #mapInnerRoute }

## Description

Changes the execution model of the inner route by wrapping it with arbitrary logic.

The `mapInnerRoute` directive is used as a building block for @ref[Custom Directives](../custom-directives.md#custom-directives) to replace the inner route
with any other route. Usually, the returned route wraps the original one with custom execution logic.

## Example

@@snip [BasicDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala) { #mapInnerRoute }