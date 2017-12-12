# onSuccess

## Description

Evaluates its parameter of type `CompletionStage<T>`, and once it has been completed successfully,
extracts its result as a value of type `T` and passes it to the inner route.

If the future fails its failure throwable is bubbled up to the nearest @unidoc[ExceptionHandler].

To handle the `Failure` case manually as well, use @ref[onComplete](onComplete.md), instead.

## Example

@@snip [FutureDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/FutureDirectivesExamplesTest.java) { #onSuccess }