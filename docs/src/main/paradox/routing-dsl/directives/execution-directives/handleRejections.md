# handleRejections

@@@ div { .group-scala }

## Signature

@@signature [ExecutionDirectives.scala](/akka-http/src/main/scala/akka/http/scaladsl/server/directives/ExecutionDirectives.scala) { #handleRejections }

@@@

## Description

Using this directive is an alternative to using a global implicitly defined `RejectionHandler` that
applies to the complete route.

See @ref[Rejections](../../rejections.md) for general information about options for handling rejections.

## Example

Scala
:  @@snip [ExecutionDirectivesExamplesSpec.scala](/docs/src/test/scala/docs/http/scaladsl/server/directives/ExecutionDirectivesExamplesSpec.scala) { #handleRejections }

Java
:  @@snip [ExecutionDirectivesExamplesTest.java](/docs/src/test/java/docs/http/javadsl/server/directives/ExecutionDirectivesExamplesTest.java) { #handleRejections }
