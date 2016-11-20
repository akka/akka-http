<a id="rejectemptyresponse-java"></a>
# rejectEmptyResponse

## Description

Replaces a response with no content with an empty rejection.

The `rejectEmptyResponse` directive is can be used whenever responding with an empty `Entity` should be a rejection 
instead of an `OK` response. One easy example where this can be used is when the entity is a `String` and the 
empty `String` should be handled as `404 Not Found`.

## Warning

Java's `Optional<T>` does not behave like Scala's `Option[T]` as it's rendered as an own `Entity` instead of being 
rendered according the parametrized `T` type, if present.

This implies that, a non-empty `Optional` is rendered like `{"present":true}` and an empty one like `{"present":false}`.

## Example

@@snip [MiscDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/MiscDirectivesExamplesTest.java) { #rejectEmptyResponse }