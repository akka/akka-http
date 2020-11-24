# extractDataBytes

@@@ div { .group-scala }

## Signature

@@signature [BasicDirectives.scala](/akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala) { #extractDataBytes }

@@@

## Description

Extracts the entities data bytes as @apidoc[Source[ByteString, \_]] from the @apidoc[RequestContext].

The directive returns a stream containing the request data bytes.

## Example

Scala
:  @@snip [BasicDirectivesExamplesSpec.scala](/docs/src/test/scala/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala) { #extractDataBytes-example }

Java
:  @@snip [BasicDirectivesExamplesTest.java](/docs/src/test/java/docs/http/javadsl/server/directives/BasicDirectivesExamplesTest.java) { #extractDataBytes }
