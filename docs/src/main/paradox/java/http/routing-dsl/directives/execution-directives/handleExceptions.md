# handleExceptions

## Description

Catches exceptions thrown by the inner route and handles them using the specified @unidoc[ExceptionHandler].

Using this directive is an alternative to using a global implicitly defined @unidoc[ExceptionHandler] that
applies to the complete route.

See @ref[Exception Handling](../../exception-handling.md) for general information about options for handling exceptions.

## Example

@@snip [ExecutionDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/ExecutionDirectivesExamplesTest.java) { #handleExceptions }