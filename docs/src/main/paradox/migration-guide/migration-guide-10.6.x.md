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
