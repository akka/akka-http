# requestEntityEmpty

## Description

A filter that checks if the request entity is empty and only then passes processing to the inner route.
Otherwise, the request is rejected.

See also @ref[requestEntityPresent](requestEntityPresent.md) for the opposite effect.

## Example

@@snip [MiscDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/MiscDirectivesExamplesTest.java) { #requestEntity-empty-present-example }