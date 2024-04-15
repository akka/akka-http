# jwt

@@@ div { .group-scala }

## Signature

@@signature [JwtDirectives.scala](/akka-http-jwt/src/main/scala/akka/http/jwt/scaladsl/server/directives/JwtDirectives.scala) { #jwt }

@@@

## Description

This directive provides a way to validate a JSON Web Token (JWT) from a request and extracts its claims for further processing. For details on what a valid JWT is, see [jwt.io](https://jwt.io/) or consult [RFC 7519](https://datatracker.ietf.org/doc/html/rfc7519).

JWTs are validated against a predefined secret or public key, depending on the used algorithm, and provided by configuration. The directive uses config defined under `akka.http.jwt`, or an explicitly provided `JwtSettings` instance.

## Dependency

The Akka dependencies are available from Akka's library repository. To access them there, you need to configure the URL for this repository.

@@repository [sbt,Gradle,Maven] {
id="akka-repository"
name="Akka library repository"
url="https://repo.akka.io/maven"
}

To use Akka HTTP Caching, add the module to your project:

@@dependency [sbt,Gradle,Maven] {
bomGroup2="com.typesafe.akka" bomArtifact2="akka-http-bom_$scala.binary.version$" bomVersionSymbols2="AkkaHttpVersion"
symbol="AkkaHttpVersion"
value="$project.version$"
group="com.typesafe.akka"
artifact="akka-http-jwt_$scala.binary.version$"
version="AkkaHttpVersion"
}

## Example

The `jwt` directive will extract and validate a JWT from the request and provide the extracted claims to the inner route in the format of a `JwtClaims` instance, which offers utility methods to extract a specific claims:

Scala
:  @@snip [JwtDirectivesExamplesSpec.scala](/docs/src/test/scala/docs/http/scaladsl/server/directives/JwtDirectivesExamplesSpec.scala) { #jwt }

Java
:  @@snip [JwtDirectivesExamplesTest.java](/docs/src/test/java/docs/http/javadsl/server/directives/JwtDirectivesExamplesTest.java) { #jwt }



## Reference configuration

@@snip [reference.conf](/akka-http-jwt/src/main/resources/reference.conf) { #jwt }