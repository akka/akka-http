# decodeRequest

@@@ div { .group-scala }

## Signature

@@signature [CodingDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/CodingDirectives.scala) { #decodeRequest }

@@@

## Description

Decompresses the incoming request if it is `gzip` or `deflate` compressed. Uncompressed requests are passed through untouched.
If the request encoded with another encoding the request is rejected with an @apidoc[UnsupportedRequestEncodingRejection].
If the request entity after decoding exceeds `akka.http.routing.decode-max-size` the stream fails with an
@scala[@scaladoc[EntityStreamSizeException](akka.http.scaladsl.model.EntityStreamSizeException)]@java[@javadoc[EntityStreamSizeException](akka.http.scaladsl.model.EntityStreamSizeException)].


## Example

Scala
:  @@snip [CodingDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/CodingDirectivesExamplesSpec.scala) { #decodeRequest }

Java
:  @@snip [CodingDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/CodingDirectivesExamplesTest.java) { #decodeRequest }
