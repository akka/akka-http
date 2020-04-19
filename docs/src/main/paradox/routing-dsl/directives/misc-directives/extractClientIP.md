# extractClientIP

@@@ div { .group-scala }

## Signature

@@signature [MiscDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/MiscDirectives.scala) { #extractClientIP }

@@@

## Description

Provides the value of `X-Forwarded-For`, `Remote-Address`, `X-Real-IP` headers or `Attributes.remoteAddress` attribute as an instance of `RemoteAddress` in the order. When the value is an invalid IP address in the header first seen and the attribute is absent, then this extractor will return `RemoteAddress.Unknown`.

The akka-http server engine adds the `Remote-Address` header to every request automatically if the respective
setting `akka.http.server.remote-address-header` is set to `on`, and adds the `Attributes.remoteAddress` attribute if the
setting `akka.http.server.remote-address-attribute` is set to `on`. Per default it is set to `off`.

@@@ note
The setting `akka.http.server.remote-address-header` will be deprecated because the producing `Remote-Address` header is synthetic and confused,
and will cover a real `Remote-Address` header passed by the client. If you want a direct http client IP address, please use `akka.http.server.remote-address-attribute` instead.
@@@

@@@ warning
Clients can send any values in these headers. If the client is not a trusted upstream, the IP address can be malicious and by pass your security rules.
@@@

## Example

Scala
:  @@snip [MiscDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/MiscDirectivesExamplesSpec.scala) { #extractClientIP-example }

Java
:  @@snip [MiscDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/MiscDirectivesExamplesTest.java) { #extractClientIPExample }
