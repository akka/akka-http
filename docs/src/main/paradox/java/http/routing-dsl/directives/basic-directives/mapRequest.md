<a id="maprequest-java"></a>
# mapRequest

## Description

Transforms the request before it is handled by the inner route.

The `mapRequest` directive is used as a building block for @ref[Custom Directives-java](../custom-directives.md#custom-directives-java) to transform a request before it
is handled by the inner route. Changing the `request.uri` parameter has no effect on path matching in the inner route
because the unmatched path is a separate field of the `RequestContext` value which is passed into routes. To change
the unmatched path or other fields of the `RequestContext` use the @ref[mapRequestContext-java](mapRequestContext.md#maprequestcontext-java) directive.

See @ref[Request Transforming Directives-java](index.md#request-transforming-directives-java) for an overview of similar directives.

## Example

@@snip [BasicDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/BasicDirectivesExamplesTest.java) { #mapRequest }