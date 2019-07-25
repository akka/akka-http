# RouteDirectives

The @apidoc[RouteDirectives] have a special role in akka-http's routing DSL. Contrary to all other directives (except most
@ref[FileAndResourceDirectives](../file-and-resource-directives/index.md)) they do not produce instances of type `Directive[L <: HList]` but rather "plain"
routes of type @scala[@scaladoc[Route](akka.http.scaladsl.server.index#Route=akka.http.scaladsl.server.RequestContext=%3Escala.concurrent.Future[akka.http.scaladsl.server.RouteResult])]@java[@apidoc[Route]].
The reason is that the @apidoc[RouteDirectives] are not meant for wrapping an inner route (like most other directives, as
intermediate-level elements of a route structure, do) but rather form the leaves of the actual route structure **leaves**.

So in most cases the inner-most element of a route structure branch is one of the @apidoc[RouteDirectives] (or
@ref[FileAndResourceDirectives](../file-and-resource-directives/index.md)):

@@toc { depth=1 }

@@@ index

* [complete](complete.md)
* [failWith](failWith.md)
* [redirect](redirect.md)
* [reject](reject.md)

@@@