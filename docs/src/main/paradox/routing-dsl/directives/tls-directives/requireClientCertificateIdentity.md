# requireClientCertificateIdentity

@@@ div { .group-scala }

## Signature

@@signature [TlsDirectives.scala](/akka-http/src/main/scala/akka/http/scaladsl/server/directives/TlsDirectives.scala) { #requireClientCertificateIdentity }

@@@

## Description

This directive allows for matching a regular expression against the identity of a client mTLS certificate. 

Require the client to be authenticated, if not reject the request with a @apidoc[TlsClientUnverifiedRejection],
(can only happen with `setWantClientAuth(true)`, if `setNeedClientAuth(true)` the connection is denied earlier), 
also require that one of the client certificate `ip` or `dns` SANs (Subject Alternative Name) or if non exists, the CN (Common Name)
to match the given regular expression, if not the request is rejected with a @apidoc[TlsClientIdentityRejection].

@@@ note
Using this directives requires tls-session info parsing to be enabled: `akka.http.server.parsing.tls-session-info-header = on` and
that the server ConnectionContext SSLEngine was set up with either `setWantClientAuth(true)` or `setNeedClientAuth(true)`
@@@

## Example

Scala
:  @@snip [TimeoutDirectivesExamplesSpec.scala](/akka-http-tests/src/test/scala/akka/http/scaladsl/server/directives/TlsDirectiveSpec.scala) { #client-cert-identity }

Java
:  @@snip [TimeoutDirectivesExamplesTest.java](/docs/src/test/java/docs/http/javadsl/server/directives/TlsDirectivesExamplesTest.java) { #client-cert-identity }
