# recoverRejectionsWith

## Description

**Low level directive** – unless you're sure you need to be working on this low-level you might instead
want to try the @ref[handleRejections](../execution-directives/handleRejections.md) directive which provides a nicer DSL for building rejection handlers.

Transforms rejections from the inner route with a `Function<Iterable<Rejection>, CompletionStage<RouteResult>>`.

Asynchronous version of @ref[recoverRejections](recoverRejections.md).

See @ref[recoverRejections](recoverRejections.md) (the synchronous equivalent of this directive) for a detailed description.

@@@ note
To learn more about how and why rejections work read the @ref[Rejections](../../rejections.md) section of the documentation.
@@@

## Example

@@snip [BasicDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/BasicDirectivesExamplesTest.java) { #recoverRejectionsWith }
