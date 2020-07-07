# extractUpgradeToWebSocket

@@@ div { .group-scala }

## Signature

@@signature [WebSocketDirectives.scala](/akka-http/src/main/scala/akka/http/scaladsl/server/directives/WebSocketDirectives.scala) { #extractUpgradeToWebSocket }

@@@

## Description

This directive is deprecated.

If you are looking for a building block for @ref[Custom Directives](../custom-directives.md) to provide the websocket upgrade information to the inner route,
we recommend using the @apidoc[WebSocketUpgrade] @ref[attribute](../../../common/http-model.md#attributes) instead:

## Example

Scala
:  @@snip [WebSocketDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/WebSocketDirectivesExamplesSpec.scala) { #webSocketUpgradeAttribute }

Java
:  @@snip [WebSocketDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/WebSocketDirectivesExamplesTest.java) { #webSocketUpgradeAttribute }
