# extractSslSession

@@@ div { .group-scala }

## Signature

@@signature [TlsDirectives.scala](/akka-http/src/main/scala/akka/http/scaladsl/server/directives/TlsDirectives.scala) { #extractSslSession }

@@@

## Description

This directive extracts the SSL session for the client/connection where the request was made. 

@@@ note
Using this directive requires tls-session info parsing to be enabled: `akka.http.server.parsing.tls-session-info-header = on`
@@@