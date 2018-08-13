# FileAndResourceDirectives

Like the @ref[RouteDirectives](../route-directives/index.md) the @unidoc[FileAndResourceDirectives] are somewhat
special in akka-http's routing DSL. Most other directives wrap an inner route and are therefore used as inner nodes of
the route tree. These directives, instead, are indeed instance of @scala[@scaladoc[Route](akka.http.scaladsl.server.index#Route=akka.http.scaladsl.server.RequestContext=%3Escala.concurrent.Future[akka.http.scaladsl.server.RouteResult])]@java[@unidoc[Route]], i.e. **leaves** of the route tree that handle a
request themselves without passing it on to an inner route.

So in most cases the inner-most element of a route structure branch is one of the @ref[RouteDirectives](../route-directives/index.md) or
@unidoc[FileAndResourceDirectives].

@@toc { depth=1 }

@@@ index

* [getFromBrowseableDirectories](getFromBrowseableDirectories.md)
* [getFromBrowseableDirectory](getFromBrowseableDirectory.md)
* [getFromDirectory](getFromDirectory.md)
* [getFromFile](getFromFile.md)
* [getFromResource](getFromResource.md)
* [getFromResourceDirectory](getFromResourceDirectory.md)
* [listDirectoryContents](listDirectoryContents.md)

@@@