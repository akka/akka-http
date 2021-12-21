# withMdcEntries

@@@ div { .group-scala }

## Signature

@@signature [MdcLoggingDirectives.scala](/akka-http/src/main/scala/akka/http/scaladsl/server/directives/MdcLoggingDirectives.scala) { #withMdcEntries }

@@@

## Description

Adds one or more (key, value) entries to the current MDC logging context.

Nested calls will accumulate entries.

## Example

Scala
:  @@snip [MdcLoggingDirectivesExamplesSpec.scala](/docs/src/test/scala/docs/http/scaladsl/server/directives/MdcLoggingDirectivesExamplesSpec.scala) { #withMdcEntries }
