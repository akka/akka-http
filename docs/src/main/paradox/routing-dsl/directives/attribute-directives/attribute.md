# attribute

@@@ div { .group-scala }

## Signature

@@signature [AttributeDirectives.scala](/akka-http/src/main/scala/akka/http/scaladsl/server/directives/AttributeDirectives.scala) { #attribute }

@@@

## Description

Extracts the value of the request @ref[attribute](../../../common/http-model.md#attributes) with the given key.

If no attribute is found for the given key the request
is rejected with a @apidoc[MissingAttributeRejection].

If the attribute is expected to be missing in some cases or to customize
handling when the header is missing use the @ref[optionalAttribute](optionalAttribute.md) directive instead.

## Example

Scala
:  @@snip [AttributeDirectivesExamplesSpec.scala](/docs/src/test/scala/docs/http/scaladsl/server/directives/AttributeDirectivesExamplesSpec.scala) { #attribute }

Java
:  @@snip [AttributeDirectivesExamplesTest.java](/docs/src/test/java/docs/http/javadsl/server/directives/AttributeDirectivesExamplesTest.java) { #attribute }
