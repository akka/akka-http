# optionalHeaderValue

## Description

Traverses the list of request headers with the specified function and extracts the first value the function returns a non empty `Optional<T>`.

The `optionalHeaderValue` directive is similar to the @ref[headerValue](headerValue.md) directive but always extracts an `Option`
value instead of rejecting the request if no matching header could be found.

## Example

@@snip [HeaderDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/HeaderDirectivesExamplesTest.java) { #optionalHeaderValue }