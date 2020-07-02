# handle

@@@ div { .group-scala }

## Signature

@@signature [RouteDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/RouteDirectives.scala) { #handle }

@@@

## Description

Creates an @apidoc[Route] that handles the request using a function from @apidoc[HttpRequest] to a @scala[`Future`]@java[`CompletionStage`] of @apidoc[HttpResponse].
