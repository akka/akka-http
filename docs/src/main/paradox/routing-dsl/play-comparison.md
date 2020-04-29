# Compared with Play routes

If you have been using @scala[[Play's routes file syntax](https://www.playframework.com/documentation/2.8.x/ScalaRouting#The-routes-file-syntax)]@java[[Play's routes file syntax](https://www.playframework.com/documentation/2.8.x/JavaRouting#The-routes-file-syntax)] earlier, this page may help you to use the Akka HTTP routing DSL.

## Conceptual differences

The most apparent difference is Play's use of special purpose syntax implemented as an [external DSL](https://en.wikipedia.org/wiki/Domain-specific_language#External_and_Embedded_Domain_Specific_Languages), whereas Akka HTTP routes are described in @scala[Scala source code]@java[Java source code] with regular methods and values (as "embedded DSL"). Both are crafted to make the reader "grasp the code's intention".

The Akka HTTP DSL uses @ref[Directives](directives/index.md) to describe how incoming requests translate to functionality in the server. Play allows splitting the routes definitions in multiple routes files. The Akka HTTP DSL is very flexible and allows for composition so that different concerns can be properly split and organized as other source code would be.

Both Play and Akka HTTP choose the first matching route within the routes file/routes definition. In Play routes are listed with one route per line, in Akka HTTP multiple routes must be concatenated with the `concat` method.

## Side-by-side

These examples are a non-comprehensive list of how Play routes could be written in Akka HTTP. They try to mimic the structure which Play uses, to aid understanding, even though it might not be the most Akka HTTP-idiomatic notation. 

### Static path

For example, to exactly match incoming `GET /clients/all` requests, you can define this route in Play.

```
GET   /clients/all          controllers.Clients.list()
```

In Akka HTTP every path segment is specified as a separate `String` @scala[concatenated with the `/` method]@java[concatenated by the `slash` method on `segment`].

Scala
:   @@snip [snip](/docs/src/test/scala/docs/http/scaladsl/server/PlayRoutesComparisonSpec.scala) { #fixed }

Scala test
:   @@snip [snip](/docs/src/test/scala/docs/http/scaladsl/server/PlayRoutesComparisonSpec.scala) { #fixed-test }

Java
:   @@snip [snip](/docs/src/test/java/docs/http/javadsl/server/testkit/PlayRoutesComparisonTest.java) { #fixed }

Java test
:   @@snip [snip](/docs/src/test/java/docs/http/javadsl/server/testkit/PlayRoutesComparisonTest.java) { #fixed-test }


### Dynamic parts

If you want to define a route that retrieves a client by ID, you’ll need to add a dynamic part.

```
GET   /clients/:id          controllers.Clients.show(id: Long)
```

Akka HTTP uses @ref[path matchers](path-matchers.md#basic-pathmatchers) which match certain data types and pass their data on.

Scala
:   @@snip [snip](/docs/src/test/scala/docs/http/scaladsl/server/PlayRoutesComparisonSpec.scala) { #long }

Scala test
:   @@snip [snip](/docs/src/test/scala/docs/http/scaladsl/server/PlayRoutesComparisonSpec.scala) { #long-test }

Java
:   @@snip [snip](/docs/src/test/java/docs/http/javadsl/server/testkit/PlayRoutesComparisonTest.java) { #long }

Java test
:   @@snip [snip](/docs/src/test/java/docs/http/javadsl/server/testkit/PlayRoutesComparisonTest.java) { #long-test }


### Dynamic parts spanning several /

You may want to capture a dynamic part of more than one URI path segment, separated by forward slashes.

```
GET   /files/*name          controllers.Application.download(name)
```

The Akka HTTP directive @scala[`Remaining`]@java[remaining()] makes a list of the segments to be passed. (See @ref[Path Matchers](path-matchers.md#basic-pathmatchers) for other ways to extract the path.)

Scala
:   @@snip [snip](/docs/src/test/scala/docs/http/scaladsl/server/PlayRoutesComparisonSpec.scala) { #remaining }

Scala test
:   @@snip [snip](/docs/src/test/scala/docs/http/scaladsl/server/PlayRoutesComparisonSpec.scala) { #remaining-test }

Java
:   @@snip [snip](/docs/src/test/java/docs/http/javadsl/server/testkit/PlayRoutesComparisonTest.java) { #remaining }

Java test
:   @@snip [snip](/docs/src/test/java/docs/http/javadsl/server/testkit/PlayRoutesComparisonTest.java) { #remaining-test }


### Access parameters

The @ref[Parameter directives](directives/parameter-directives/index.md) give access to parameters passed on the URL.

#### Mandatory parameters

By default parameters are expected to be of type `String`. To make Akka HTTP convert a parameter to a different type, specify an @ref[unmarshaller](directives/parameter-directives/parameters.md#deserialized-parameter).

```
# Extract the page parameter from the query string.
# i.e. http://myserver.com/?page=index
GET   /                     controllers.Application.show(page)
```

Scala
:   @@snip [snip](/docs/src/test/scala/docs/http/scaladsl/server/PlayRoutesComparisonSpec.scala) { #mandatory-parameter }

Scala test
:   @@snip [snip](/docs/src/test/scala/docs/http/scaladsl/server/PlayRoutesComparisonSpec.scala) { #mandatory-parameter-test }

Java
:   @@snip [snip](/docs/src/test/java/docs/http/javadsl/server/testkit/PlayRoutesComparisonTest.java) { #mandatory-parameter }

Java test
:   @@snip [snip](/docs/src/test/java/docs/http/javadsl/server/testkit/PlayRoutesComparisonTest.java) { #mandatory-parameter-test }


#### Optional parameters
```
# The version parameter is optional. E.g. /api/list-all?version=3.0
GET   /api/list-all         controllers.Api.list(version: Option[String])
```

@@@ div { .group-scala }
The parameter name may be decorated with `.optional` to mark it as optional (for other variants see @ref[other parameter extractors](directives/parameter-directives/parameters.md#description)).
@@@
@@@ div { .group-java }
The `parameterOptional` directive passes the parameter as `Optional<String>`. 

The directive `parameterRequiredValue` makes the route match only if the parameter contains the specified value.

See @ref[parameter extractors](directives/parameter-directives/parameters.md).
@@@

Scala
:   @@snip [snip](/docs/src/test/scala/docs/http/scaladsl/server/PlayRoutesComparisonSpec.scala) { #optional-parameter }

Scala test
:   @@snip [snip](/docs/src/test/scala/docs/http/scaladsl/server/PlayRoutesComparisonSpec.scala) { #optional-parameter-test }

Java
:   @@snip [snip](/docs/src/test/java/docs/http/javadsl/server/testkit/PlayRoutesComparisonTest.java) { #optional-parameter }

Java test
:   @@snip [snip](/docs/src/test/java/docs/http/javadsl/server/testkit/PlayRoutesComparisonTest.java) { #optional-parameter-test }


#### List parameters

This shows how a repeated URL parameter is captured.

```
# The item parameter is a list.
# E.g. /api/list-items?item=red&item=new&item=slippers
GET   /api/list-items      controllers.Api.listItems(item: List[String])
```

@@@ div { .group-scala }
Decorating the parameter name with a `.repeated` makes Akka HTTP pass all values of that parameter as an `Iterable[String]`].
@@@
@@@ div { .group-java }
The `parameterList` directive may take a parameter name to specify a single parameter name to pass on as a `List<String>`.]
@@@

Scala
:   @@snip [snip](/docs/src/test/scala/docs/http/scaladsl/server/PlayRoutesComparisonSpec.scala) { #parameter-list }

Scala test
:   @@snip [snip](/docs/src/test/scala/docs/http/scaladsl/server/PlayRoutesComparisonSpec.scala) { #parameter-list-test }

Java
:   @@snip [snip](/docs/src/test/java/docs/http/javadsl/server/testkit/PlayRoutesComparisonTest.java) { #parameter-list }

Java test
:   @@snip [snip](/docs/src/test/java/docs/http/javadsl/server/testkit/PlayRoutesComparisonTest.java) { #parameter-list-test }
