# optionalAttribute

@@@ div { .group-scala }

## Signature

@@signature [AttributeDirectives.scala](/akka-http/src/main/scala/akka/http/scaladsl/server/directives/HeaderDirectives.scala) { #optionalAttribute }

@@@

## Description

Optionally extracts the value of the request @ref[attribute](../../../common/http-model.md#attributes) with the given key.

The `optionalAttribute` directive is similar to the @ref[attribute](attribute.md) directive but always extracts
an @scala[`Option`]@java[`Optional`] value instead of rejecting the request if no matching attribute could be found.

## Example

Scala
:  @@snip [AttributeDirectivesExamplesSpec.scala](/docs/src/test/scala/docs/http/scaladsl/server/directives/AttributeDirectivesExamplesSpec.scala) { #optionalAttribute }

Java
:  @@snip [AttributeDirectivesExamplesTest.java](/docs/src/test/java/docs/http/javadsl/server/directives/AttributeDirectivesExamplesTest.java) { #optionalAttribute }
