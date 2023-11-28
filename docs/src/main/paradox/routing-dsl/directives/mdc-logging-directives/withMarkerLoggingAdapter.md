# withMarkerLoggingAdapter

@@@ div { .group-scala }

## Signature

@@signature [MdcLoggingDirectives.scala](/akka-http/src/main/scala/akka/http/scaladsl/server/directives/MdcLoggingDirectives.scala) { #withMarkerLoggingAdapter }

@@@

## Description

Replaces the @apidoc[RequestContext]'s existing @apidoc[LoggingAdapter] with a new instance of @apidoc[MarkerLoggingAdapter] and provides the same @apidoc[MarkerLoggingAdapter] to the caller. If the existing @apidoc[LoggingAdapter] already has an MDC map, the directive will copy its entries into the new @apidoc[MarkerLoggingAdapter].

## Example

Scala
:  @@snip [MdcLoggingDirectivesExamplesSpec.scala](/docs/src/test/scala/docs/http/scaladsl/server/directives/MdcLoggingDirectivesExamplesSpec.scala) { #withMarkerLoggingAdapter }
