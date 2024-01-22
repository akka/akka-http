# Migration Guide to and within Akka HTTP 10.6.x

## General Notes

See the general @ref[compatibility guidelines](../compatibility-guidelines.md).

Under these guidelines, minor version updates are supposed to be binary compatible and drop-in replacements
for former versions under the condition that user code only uses public, stable, non-deprecated API.

If you find an unexpected incompatibility please let us know.

No configuration changes are needed for updating an application from Akka HTTP 10.5.x to 10.6.x.

The `akka-http2-support` module, which was an empty place-holder artifact, is no longer published and needs to be removed
from builds using Akka. HTTP/2 support is provided by the akka-http-core module, no new modules needs to be added to a project
using HTTP/2.

The typesafe-ssl-config dependency and deprecated APIs accepting the types from it has been dropped. 

## Akka repository

The Akka dependencies are available from Akka's library repository. To access them there, you need to configure the URL for this repository.

@@repository [sbt,Maven,Gradle] {
id="akka-repository"
name="Akka library repository"
url="https://repo.akka.io/maven"
}

## Support for Java 8 removed

The published artifacts are targeting Java 11, and later. Supported Java versions are 11 and 17.

## Support for Scala 2.12 removed

The published artifacts are targeting Scala 2.13 and Scala 3.3.

## Dependency updates

### Akka

Akka HTTP 10.6.x requires Akka version >= 2.9.0.

### Jackson

The Jackson dependency has been updated to 2.15.2 in Akka HTTP 10.6.0. That bump includes many fixes and changes to
Jackson, but it should not introduce any incompatibility in serialized format.

### Built in CORS support

Built in directives with CORS support @ref[has been added](../routing-dsl/directives/cors-directives/cors.md) heavily inspired
by the pre-existing community library [akka-http-cors](https://github.com/lomigmegard/akka-http-cors).

Directive API and configuration are similar and migrating should be straightforward. Some of the lower level APIs for implementing
CORS that the library gave access to (`HttpOriginMatcher`) and the `CorsRejection` implementation 
is simplified or not available as public API in the new Akka HTTP CORS implementation.

The new configuration namespace is `akka.http.cors` instead of `akka-http-cors`, the individual setting names are the same
however `allowed-origins`, `allowed-headers` are always lists of values with a single `["*"]` to represent match-any.