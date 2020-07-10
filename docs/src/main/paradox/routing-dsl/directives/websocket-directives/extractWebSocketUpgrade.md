# extractWebSocketUpgrade

@@@ div { .group-scala }

## Signature

@@signature [WebSocketDirectives.scala](/akka-http/src/main/scala/akka/http/scaladsl/server/directives/WebSocketDirectives.scala) { #extractWebSocketUpgrade }

@@@

## Description

The `extractWebSocketUpgrade` directive is used as a building block for @ref[Custom Directives](../custom-directives.md) to provide the websocket upgrade information to the inner route.

## Example

Scala
:  @@snip [WebSocketDirectivesExamplesSpec.scala](/docs/src/test/scala/docs/http/scaladsl/server/directives/WebSocketDirectivesExamplesSpec.scala) { #extractWebSocketUpgrade }

Java
:  @@snip [WebSocketDirectivesExamplesTest.java](/docs/src/test/java/docs/http/javadsl/server/directives/WebSocketDirectivesExamplesTest.java) { #extractWebSocketUpgrade }
