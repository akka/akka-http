<a id="ignoreTrailingSlash-java"></a>
# ignoreTrailingSlash

## Description

If the requested path ends with a trailing `/` character and the inner route is rejected with an empty `Rejection` list, 
it retries it removing the trailing `/` character. Similarly, it retries adding a trailing `/` character if the original requested path doesn't end with a `/` character. 

This directive will retry the inner route with a "flipped" trailing slash only if the mentioned inner route is not handled
producing an empty `Rejection` list.

Please note that enclosing big routes with this directive might cause double evaluation in case of not handled requested paths.

See also @ref[redirectToNoTrailingSlashIfPresent](redirectToNoTrailingSlashIfPresent.md#redirecttonotrailingslashifpresent) and @ref[redirectToTrailingSlashIfMissing](redirectToTrailingSlashIfMissing.md#redirecttotrailingslashifmissing) for other ways to accomplish a similar thing. 

## Example

@@snip [PathDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/PathDirectivesExamplesTest.java) { #ignoreTrailingSlash }