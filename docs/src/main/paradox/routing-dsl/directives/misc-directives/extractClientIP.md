# extractClientIP

@@@ div { .group-scala }

## Signature

@@signature [MiscDirectives.scala](/akka-http/src/main/scala/akka/http/scaladsl/server/directives/MiscDirectives.scala) { #extractClientIP }

@@@

## Description

Provides the value of the `X-Forwarded-For` or `X-Real-IP` header.
If neither of those is found it will fall back to the value of the synthetic `RemoteAddress` header (`akka.http.server.remote-address-header` setting is `on`)
or the value of the @apidoc[AttributeKeys.remoteAddress](AttributeKeys$) @ref[attribute](../../../common/http-model.md#attributes)  (if the `akka.http.server.remote-address-attribute` setting is `on`)

If no valid IP address is encountered, this extractor will return RemoteAddress.Unknown`.

@@@ warning
Clients can send any values in these headers. If the client is not a trusted upstream, the IP address can be malicious.
For sensitive operations use the @apidoc[AttributeKeys.remoteAddress](AttributeKeys$) @ref[attribute](../../../common/http-model.md#attributes),
or use the specific headers which are known to be set correctly by the infrastructure you do trust.
@@@

## Example

Scala
:  @@snip [MiscDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/MiscDirectivesExamplesSpec.scala) { #extractClientIP-example }

Java
:  @@snip [MiscDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/MiscDirectivesExamplesTest.java) { #extractClientIPExample }
