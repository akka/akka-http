# handle

@@@ div { .group-scala }

## Signature

@@signature [RouteDirectives.scala](/akka-http/src/main/scala/akka/http/scaladsl/server/directives/RouteDirectives.scala) { #handle }

@@@

## Description

Creates a @scala[@scaladoc[Route](akka.http.scaladsl.server.index#Route=akka.http.scaladsl.server.RequestContext=%3Escala.concurrent.Future[akka.http.scaladsl.server.RouteResult])]@java[@javadoc[Route](akka.http.javadsl.server.Route)]
that handles the request using a function or `PartialFunction` from @apidoc[HttpRequest] to a @scala[`Future`]@java[`CompletionStage`] of @apidoc[HttpResponse].

This directive can be used to include external components request processing components defined as a `Function` or `PartialFunction`
(like [those provided by akka-grpc](https://doc.akka.io/docs/akka-grpc/current/server/walkthrough.html#serving-multiple-services))
into a routing tree defined by directives and routes.

For the `PartialFunction` variant, the given list of rejections will be used to reject the request with if the `PartialFunction` is not defined for a request. By default,
an empty list of rejections will be used which is interpreted as "Not Found".

There is also a strict version called @ref[handleSync](handleSync.md).

## Example

Scala
:  @@snip [RouteDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/RouteDirectivesExamplesSpec.scala) { #handle-examples-with-PF }