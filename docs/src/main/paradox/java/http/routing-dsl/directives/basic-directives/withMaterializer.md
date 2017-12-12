# withMaterializer

## Description

Allows running an inner route using an alternative @unidoc[Materializer] in place of the default one.

The materializer can be extracted in an inner route using @ref[extractMaterializer](extractMaterializer.md) directly,
or used by directives which internally extract the materializer without surfacing this fact in the API
(e.g. responding with a Chunked entity).

## Example

@@snip [BasicDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/BasicDirectivesExamplesTest.java) { #withMaterializer }