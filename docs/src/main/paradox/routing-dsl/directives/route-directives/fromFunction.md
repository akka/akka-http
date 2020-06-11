# fromFunction

@@@ div { .group-scala }

## Signature

@@signature [RouteDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/RouteDirectives.scala) { #fromFunction }

@@@

## Description

Takes a function from @apidoc[HttpRequest] to a @scala[`Future`]@java[`CompletionStage`] of @apidoc[HttpResponse]
and turns it into a @apidoc[Route].