# decodeRequestWith

@@@ div { .group-scala }

## Signature

@@signature [CodingDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/CodingDirectives.scala) { #decodeRequestWith }

@@@

## Description

Decodes the incoming request if it is encoded with one of the given encoders.
If the request encoding doesn't match one of the given encoders the request is rejected with an @apidoc[UnsupportedRequestEncodingRejection]. If no decoders are given the default encoders (`Gzip`, `Deflate`, `NoCoding`) are used.
If the request entity after decoding exceeds `akka.http.routing.decode-max-size` the stream fails with an
@apidoc[akka.http.scaladsl.model.EntityStreamSizeException].


## Example

Scala
:  @@snip [CodingDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/CodingDirectivesExamplesSpec.scala) { #decodeRequestWith }

Java
:  @@snip [CodingDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/CodingDirectivesExamplesTest.java) { #decodeRequestWith }
