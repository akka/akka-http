# withMdcLogging

@@@ div { .group-scala }

## Signature

@@signature [MdcLoggingDirectives.scala](/akka-http/src/main/scala/akka/http/scaladsl/server/directives/MdcLoggingDirectives.scala) { #withMdcLogging }

@@@

## Description

Replaces the @apidoc[RequestContext]'s existing @apidoc[LoggingAdapter] with an MDC-compatible @apidoc[MarkerLoggingAdapter].

Nested calls will provide the same instance of `MarkerLoggingAdapter`.

## Example

Scala
:  @@snip [MdcLoggingDirectivesExamplesSpec.scala](/docs/src/test/scala/docs/http/scaladsl/server/directives/MdcLoggingDirectivesExamplesSpec.scala) { #withMdcLogging }
