# handle

@@@ div { .group-scala }

## Signature

@@signature [RouteDirectives.scala](/akka-http/src/main/scala/akka/http/scaladsl/server/directives/RouteDirectives.scala) { #handle }

@@@

## Description

Creates an @scala[@scaladoc[Route](akka.http.scaladsl.server.index#Route=akka.http.scaladsl.server.RequestContext=%3Escala.concurrent.Future[akka.http.scaladsl.server.RouteResult])]@java[@javadoc[Route](akka.http.javadsl.server.Route)] that handles the request using a function from @apidoc[HttpRequest] to a @scala[`Future`]@java[`CompletionStage`] of @apidoc[HttpResponse].
