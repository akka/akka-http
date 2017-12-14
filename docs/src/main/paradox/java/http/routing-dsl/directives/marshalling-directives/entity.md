# entity

@@@ div { .group-scala }

## Signature

@@signature [MarshallingDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/MarshallingDirectives.scala) { #entity }

@@@

## Description

Unmarshalls the request entity to the given type and passes it to its inner Route.  An unmarshaller
returns an `Either` with `Right(value)` if successful or `Left(exception)` for a failure.
The `entity` method will either pass the `value` to the inner route or map the `exception` to a
`akka.http.javadsl.server.Rejection`.

The `entity` directive works in conjunction with `as` and `akka.http.scaladsl.unmarshalling` to
convert some serialized "wire format" value into a higher-level object structure.  
@ref[The unmarshalling documentation](../../../common/unmarshalling.md) explains this process in detail.
This directive simplifies extraction and error handling to the specified type from the request.

An unmarshaller will return a `Left(exception)` in the case of an error.  This is converted to a
`akka.http.scaladsl.server.Rejection` within the `entity` directive.  The following table lists how exceptions
are mapped to rejections:

|Left(exception)          | Rejection                                                                |
|-------------------------|--------------------------------------------------------------------------|
|`ContentExpected`        | @unidoc[RequestEntityExpectedRejection]                                         |
|`UnsupportedContentType` | @unidoc[UnsupportedRequestContentTypeRejection], which lists the supported types|
|`MaformedContent`        | @unidoc[MalformedRequestContentRejection], with an error message and cause      |

## Examples

The following example uses @ref[Json Support via Jackson](../../../common/json-support.md#json-jackson-support) to unmarshall a json request into a simple `Person`  

@@snip [MarshallingDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/MarshallingDirectivesExamplesTest.java) { #person }

Scala
:  @@snip [MarshallingDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/MarshallingDirectivesExamplesSpec.scala) { #example-entity-with-raw-json }

Java
:  @@snip [MarshallingDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/MarshallingDirectivesExamplesTest.java) { #example-entity-with-json }
