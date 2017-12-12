# delete

Matches requests with HTTP method `DELETE`.

## Description

This directive filters an incoming request by its HTTP method. Only requests with
method `DELETE` are passed on to the inner route. All others are rejected with a
@unidoc[MethodRejection], which is translated into a `405 Method Not Allowed` response
by the default `RejectionHandler`.

## Example

@@snip [MethodDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/MethodDirectivesExamplesTest.java) { #delete }