# handleSync

@@@ div { .group-scala }

## Signature

@@signature [RouteDirectives.scala](/akka-http/src/main/scala/akka/http/scaladsl/server/directives/RouteDirectives.scala) { #handleSync }

@@@

## Description

Creates a @scala[@scaladoc[Route](akka.http.scaladsl.server.index#Route=akka.http.scaladsl.server.RequestContext=%3Escala.concurrent.Future[akka.http.scaladsl.server.RouteResult])]@java[@javadoc[Route](akka.http.javadsl.server.Route)]
that handles the request using a function or `PartialFunction` from @apidoc[HttpRequest] to a @apidoc[HttpResponse].

This directive can be used to include components into a routing tree that have been defined only in terms of the low-level model classes. 

This is a strict version of @ref[handle](handle.md).

## Example

Scala
:  @@snip [RouteDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/RouteDirectivesExamplesSpec.scala) { #handleSync-examples-with-PF }