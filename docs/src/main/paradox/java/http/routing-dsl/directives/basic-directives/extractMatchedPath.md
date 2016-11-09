<a id="extractmatchedpath-java"></a>
# extractMatchedPath

## Description

Extracts the request's matched path.

This directive is used to acquire part of path matched during routing matching
process. Similar but opposite to `RequestContext` unmatched path property.

See also @ref[extractUnmatchedPath](extractUnmatchedPath.md#extractunmatchedpath-java) to see similar directive for unmatched path.

## Example

@@snip [BasicDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/BasicDirectivesExamplesTest.java) { #extractMatchedPath }