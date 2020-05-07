# Routing DSL style guide

Akka HTTP's routing DSL is at the center of most Akka HTTP-based servers. It's where the incoming requests diverge into the different parts of the implemented services.

Keeping all routing in one big structure will easily become hard to grasp and maintain. This page gives a few hints for how you may want to break down the routing logic.

### Main recommendations

1. Most `Route`s consist of multiple `Route`s in themselves, isolate them into values or methods.
1. Directives combine into other directives, isolate repeated combinations into values.
1. Keep the most static part of a route outermost (eg. the fixed path segments), end with the HTTP methods.
1. Encapsulate patterns you want to establish into helpers.

## Structure

### Routes are built out of directives

Think of a route as a function describing how an incoming request maps to a reply (technically @scala[`RequestContext => Future[RouteResult]`]@java[`RequestContext -> CompletionStage<RouteResult>`]) (see @ref[Routes](routes.md)). A route is expressed in directives. Directives compose into new directives (see @ref[Composing directives](directives/index.md#composing-directives)).

## Paths

Keep the most static part of a route outermost (eg. the fixed path segments), end with the HTTP methods.

Scala
:   @@snip[snip](/docs/src/test/scala/docs/http/scaladsl/server/directives/StyleGuideExamplesSpec.scala) { #path-outermost }

Java
:   @@snip[snip](/docs/src/test/java/docs/http/javadsl/server/directives/StyleGuideExamplesTest.java) { #imports #path-outermost }

Group routes with a `pathPrefix` where possible, use `path` for the last bit.

Scala
:   @@snip[snip](/docs/src/test/scala/docs/http/scaladsl/server/directives/StyleGuideExamplesSpec.scala) { #path-prefix }

Java
:   @@snip[snip](/docs/src/test/java/docs/http/javadsl/server/directives/StyleGuideExamplesTest.java) { #imports #path-prefix }

Create "sub-routes" independently and stitch them together with their prefixes.

Scala
:   @@snip[snip](/docs/src/test/scala/docs/http/scaladsl/server/directives/StyleGuideExamplesSpec.scala) { #path-compose }

Java
:   @@snip[snip](/docs/src/test/java/docs/http/javadsl/server/directives/StyleGuideExamplesTest.java) { #imports #path-compose }


### Directives

If you find yourself repeating certain directives in combination at lot, combine them to a new directive. @scala[Directives that extract values always produce a tuple.]

Scala
:   @@snip[snip](/docs/src/test/scala/docs/http/scaladsl/server/directives/StyleGuideExamplesSpec.scala) { #directives-combine }

Java
:   @@snip[snip](/docs/src/test/java/docs/http/javadsl/server/directives/StyleGuideExamplesTest.java) { #imports-directives #directives-combine }


