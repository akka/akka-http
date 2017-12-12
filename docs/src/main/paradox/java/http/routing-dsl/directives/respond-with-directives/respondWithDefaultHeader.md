# respondWithDefaultHeader

## Description

Adds a given HTTP header to all responses coming back from its inner route only if a header with the same name doesn't
exist yet in the response.

This directive transforms @unidoc[HttpResponse] and `ChunkedResponseStart` messages coming back from its inner route by
potentially adding the given @unidoc[HttpHeader] instance to the headers list.
The header is only added if there is no header instance with the same name (case insensitively) already present in the
response.

See also @ref[respondWithDefaultHeaders](respondWithDefaultHeaders.md)  if you'd like to add more than one header.

## Example

@@snip [RespondWithDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/RespondWithDirectivesExamplesTest.java) { #respondWithDefaultHeader }