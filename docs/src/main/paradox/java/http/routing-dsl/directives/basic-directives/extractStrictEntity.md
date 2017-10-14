# extractStrictEntity

## Description

Extracts the strict http entity as `HttpEntity.Strict` from the `RequestContext`.

A timeout parameter is given and if the stream isn't completed after the timeout, the directive will be failed.

@@@ warning

The directive will read the request entity into memory within the size limit(8M by default) and effectively disable streaming.
The size limit can be configured globally with `akka.http.parsing.max-content-length` or
overridden by wrapping with @ref[withSizeLimit](../misc-directives/withSizeLimit.md) or @ref[withoutSizeLimit](../misc-directives/withoutSizeLimit.md) directive.

@@@

## Example

@@snip [BasicDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/BasicDirectivesExamplesTest.java) { #extractStrictEntity }
