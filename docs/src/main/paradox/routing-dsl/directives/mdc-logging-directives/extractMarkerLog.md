# extractMarkerLog

@@@ div { .group-scala }

## Signature

@@signature [MdcLoggingDirectives.scala](/akka-http/src/main/scala/akka/http/scaladsl/server/directives/MdcLoggingDirectives.scala) { #extractMarkerLog }

@@@

## Description

Calls @ref[withMdcLogging](./withMdcLogging.md) and provides the resulting @apidoc[MarkerLoggingAdapter] from the @apidoc[RequestContext].

Nested calls will provide the same instance of `MarkerLoggingAdapter`.

## Example

Scala
:  @@snip [MdcLoggingDirectivesExamplesSpec.scala](/docs/src/test/scala/docs/http/scaladsl/server/directives/MdcLoggingDirectivesExamplesSpec.scala) { #extractMarkerLog }
