# Migration Guide to and within Akka HTTP 10.7.x

## General Notes

See the general @ref[compatibility guidelines](../compatibility-guidelines.md).

Under these guidelines, minor version updates are supposed to be binary compatible and drop-in replacements
for former versions under the condition that user code only uses public, stable, non-deprecated API.

If you find an unexpected incompatibility please let us know.

No configuration changes are needed for updating an application from Akka HTTP 10.6.x to 10.7.x.

## Akka repository

@@@note
The Akka dependencies are available from Akka’s secure library repository. To access them you need to use a secure, tokenized URL as specified at https://account.akka.io/token.
@@@

## Dependency updates

### Akka

Akka HTTP 10.7.x requires Akka version >= 2.10.0.

### Jackson

The Jackson dependency has been updated to 2.17.2 in Akka HTTP 10.7.0. That bump includes many fixes and changes to
Jackson, but it should not introduce any incompatibility in serialized format.

### Remove dependency to scala-java8-compat

The transitive dependency on scala-java8-compat has been removed.

### Support for slf4j 1.7.x and logback 1.2.x removed

This is the first release that only supports slf4j 2.0.x and logback 1.5.x.

