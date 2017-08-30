# mapInnerRoute

## Description

Changes the execution model of the inner route by wrapping it with arbitrary logic.

The `mapInnerRoute` directive is used as a building block for @ref[Custom Directives](../custom-directives.md) to replace the inner route
with any other route. Usually, the returned route wraps the original one with custom execution logic.

## Example

@@snip [BasicDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/BasicDirectivesExamplesTest.java) { #mapInnerRoute }