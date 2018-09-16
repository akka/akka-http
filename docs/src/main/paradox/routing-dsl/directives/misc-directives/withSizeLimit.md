# withSizeLimit

@@@ div { .group-scala }

## Signature

@@signature [MiscDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/MiscDirectives.scala) { #withSizeLimit }

@@@

## Description

Fails the stream with `EntityStreamSizeException` if its request entity size exceeds given limit. Limit given
as parameter overrides limit configured with `akka.http.parsing.max-content-length`.

The whole mechanism of entity size checking is intended to prevent certain Denial-of-Service attacks.
So suggested setup is to have `akka.http.parsing.max-content-length` relatively low and use `withSizeLimit`
directive for endpoints which expects bigger entities.

See also @ref[withoutSizeLimit](withoutSizeLimit.md) for skipping request entity size check.

## Examples

Scala
:   @@snip [MiscDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/MiscDirectivesExamplesSpec.scala) { #withSizeLimit-example }

Java
:   @@snip [MiscDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/MiscDirectivesExamplesTest.java) { #withSizeLimitExample }

@@@ div { .group-scala }

Beware that request entity size check is executed when entity is consumed. Therefore in the following example
even request with entity greater than argument to `withSizeLimit` will succeed (because this route
does not consume entity):

@@snip [MiscDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/MiscDirectivesExamplesSpec.scala) { #withSizeLimit-execution-moment-example }

@@@

Directive `withSizeLimit` is implemented in terms of `HttpEntity.withSizeLimit` which means that in case of
nested `withSizeLimit` directives the innermost is applied:

Scala
:   @@snip [MiscDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/MiscDirectivesExamplesSpec.scala) { #withSizeLimit-nested-example }

Java
:   @@snip [MiscDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/MiscDirectivesExamplesTest.java) { #withSizeLimitExampleNested }
