<a id="extractmatchedpathtext"></a>
# extractMatchedPath

## Signature

@@signature [BasicDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala) { #extractMatchedPath }

## Description

Extracts the request's matched path.

This directive is used to acquire part of path matched during routing matching
process. Similar but opposite to `RequestContext` unmatched path property.

See also @ref[extractUnmatchedPath](extractUnmatchedPath.md#extractunmatchedpath) to see similar directive for unmatched path.

## Example

@@snip [BasicDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala) { #extractMatchedPath-example }