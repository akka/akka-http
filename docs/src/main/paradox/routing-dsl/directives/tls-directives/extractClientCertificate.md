# extractClientCertificate

@@@ div { .group-scala }

## Signature

@@signature [TlsDirectives.scala](/akka-http/src/main/scala/akka/http/scaladsl/server/directives/TlsDirectives.scala) { #extractClientCertificate }

@@@

## Description

This directive extracts the client certificate for the client mTLS connection where the request was made.

If there is no client trusted certificate present (can only happen with `setWantClientAuth(true)`) the request is rejected with a @apidoc[TlsClientUnverifiedRejection].

@@@ note
Using this directive requires tls-session info parsing to be enabled: `akka.http.server.parsing.tls-session-info-header = on` and 
that the server ConnectionContext SSLEngine was set up with either `setWantClientAuth(true)` or `setNeedClientAuth(true)`
@@@

## Example

Scala
:  @@snip [TimeoutDirectivesExamplesSpec.scala](/akka-http-tests/src/test/scala/akka/http/scaladsl/server/directives/TlsDirectiveSpec.scala) { #client-cert }

Java
:  @@snip [TimeoutDirectivesExamplesTest.java](/docs/src/test/java/docs/http/javadsl/server/directives/TlsDirectivesExamplesTest.java) { #client-cert }
