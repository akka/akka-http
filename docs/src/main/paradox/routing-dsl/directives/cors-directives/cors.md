# cors

@@@ div { .group-scala }

## Signature

@@signature [CorsDirectives.scala](/akka-http/src/main/scala/akka/http/scaladsl/server/directives/CorsDirectives.scala) { #cors }

@@@

## Description

CORS (Cross Origin Resource Sharing) is a mechanism to enable cross origin requests by informing browsers about origins
other than the server itself that the browser can load resources from via HTTP headers.

For an overview on how CORS works, see the [MDN web docs page on CORS](https://developer.mozilla.org/en-US/docs/Web/HTTP/CORS)

The directive uses config defined under `akka.http.cors`, or an explicitly provided `CorsSettings` instance.

## Example

The `cors` directive will provide a pre-flight `OPTIONS` handler and let other requests through to the inner route: 

Scala
:  @@snip [CorsDirectivesExamplesSpec.scala](/docs/src/test/scala/docs/http/scaladsl/server/directives/CorsDirectivesExamplesSpec.scala) { #cors }

Java
:  @@snip [CorsDirectivesExamplesTest.java](/docs/src/test/java/docs/http/javadsl/server/directives/CorsDirectivesExamplesTest.java) { #cors }


## Reference configuration

@@snip [reference.conf](/akka-http/src/main/resources/reference.conf) { #cors }