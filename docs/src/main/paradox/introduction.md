# 1. Introduction

@@project-info{ projectId="akka-http" }

The Akka HTTP modules implement a full server- and client-side HTTP stack on top of *akka-actor* and *akka-stream*. It's
not a web-framework but rather a more general toolkit for providing and consuming HTTP-based services. While interaction
with a browser is of course also in scope it is not the primary focus of Akka HTTP.

Akka HTTP follows a rather open design and many times offers several different API levels for "doing the same thing".
You get to pick the API level of abstraction that is most suitable for your application.
This means that, if you have trouble achieving something using a high-level API, there's a good chance that you can get
it done with a low-level API, which offers more flexibility but might require you to write more application code.

## Philosophy

Akka HTTP has been driven with a clear focus on providing tools for building integration layers rather than application cores. As such it regards itself as a suite of libraries rather than a framework.

A framework, as we’d like to think of the term, gives you a “frame”, in which you build your application. It comes with a lot of decisions already pre-made and provides a foundation including support structures that lets you get started and deliver results quickly. In a way a framework is like a skeleton onto which you put the “flesh” of your application in order to have it come alive. As such frameworks work best if you choose them before you start application development and try to stick to the framework's “way of doing things” as you go along.

For example, if you are building a browser-facing web application it makes sense to choose a web framework and build your application on top of it because the “core” of the application is the interaction of a browser with your code on the web-server. The framework makers have chosen one “proven” way of designing such applications and let you “fill in the blanks” of a more or less flexible “application-template”. Being able to rely on best-practice architecture like this can be a great asset for getting things done quickly.

However, if your application is not primarily a web application because its core is not browser-interaction but some specialized maybe complex business service and you are merely trying to connect it to the world via a REST/HTTP interface a web-framework might not be what you need. In this case the application architecture should be dictated by what makes sense for the core not the interface layer. Also, you probably won’t benefit from the possibly existing browser-specific framework components like view templating, asset management, JavaScript- and CSS generation/manipulation/minification, localization support, AJAX support, etc.

Akka HTTP was designed specifically as “not-a-framework”, not because we don’t like frameworks, but for use cases where a framework is not the right choice. Akka HTTP is made for building integration layers based on HTTP and as such tries to “stay on the sidelines”. Therefore you normally don’t build your application “on top of” Akka HTTP, but you build your application on top of whatever makes sense and use Akka HTTP merely for the HTTP integration needs.

On the other hand, if you prefer to build your applications with the guidance of a framework, you should give [Play Framework](https://www.playframework.com/) or [Lagom](https://www.lagomframework.com/) a try, which both use Akka internally. If you
come from Play and want to try Akka HTTP, we collected a @ref[side-by-side comparison](routing-dsl/play-comparison.md) to show how some Play routing features map to the Akka HTTP routing DSL.

## Using Akka HTTP

Akka HTTP is provided as independent modules from Akka itself under its own release cycle. Akka HTTP is @ref[compatible](compatibility-guidelines.md)
with Akka 2.5, Akka 2.6 and  any later 2.x versions released during the lifetime of Akka HTTP 10.2.x. The modules, however, do *not* depend on `akka-actor` or `akka-stream`, so the user is required to
choose an Akka version to run against and add a manual dependency to `akka-stream` of the chosen version.

@@dependency [sbt,Gradle,Maven] {
  symbol1=AkkaVersion
  value1=$akka.version$
  symbol2="AkkaHttpVersion"
  value2="$project.version$"
  group1="com.typesafe.akka" artifact1="akka-actor-typed$scala.binary.version$" version1=AkkaVersion
  group2="com.typesafe.akka" artifact2="akka-stream_$scala.binary.version$" version2=AkkaVersion
  group3="com.typesafe.akka" artifact3="akka-http_$scala.binary.version$" version3="AkkaHttpVersion"
}

You may download a packaged version of this project by clicking "Create a project for me!" on the
@scala[[Lightbend Getting Started page](https://developer.lightbend.com/start/?group=akka&project=akka-http-quickstart-scala)]
@java[[Lightbend Getting Started page](https://developer.lightbend.com/start/?group=akka&project=akka-http-quickstart-java)].

Alternatively, you can bootstrap a new project with Akka HTTP already configured using the [Giter8](http://www.foundweekends.org/giter8/) template directly via sbt:

@@@ div { .group-scala }
For Scala (sbt)
:  ```sh
    sbt new akka/akka-http-quickstart-scala.g8
    ```
@@@
@@@ div { .group-java }
For Java (Maven or Gradle)
:  ```sh
    sbt new akka/akka-http-quickstart-java.g8
    ```
From there on the prepared project can be built using Gradle or Maven.
@@@

More instructions can be found on the @scala[[template
project](https://github.com/akka/akka-http-quickstart-scala.g8)]@java[[template
project](https://github.com/akka/akka-http-quickstart-java.g8)].

## Routing DSL for HTTP servers

The high-level, routing API of Akka HTTP provides a DSL to describe HTTP "routes" and how they should be handled.
Each route is composed of one or more level of @apidoc[Directives] that narrows down to handling one specific type of
request.

For example one route might start with matching the `path` of the request, only matching if it is "/hello", then
narrowing it down to only handle HTTP `get` requests and then `complete` those with a string literal, which
will be sent back as a HTTP OK with the string as response body.

The
@scala[@scaladoc[Route](akka.http.scaladsl.server.index#Route=akka.http.scaladsl.server.RequestContext=%3Escala.concurrent.Future[akka.http.scaladsl.server.RouteResult])]
@java[@javadoc[Route](akka.http.javadsl.server.Route)]
created using the Route DSL is then "bound" to a port to start serving HTTP requests:

Scala
:   @@snip [HttpServerRoutingMinimal.scala]($test$/scala/docs/http/scaladsl/HttpServerRoutingMinimal.scala)

Java
:   @@snip [HttpServerMinimalExampleTest.java]($test$/java/docs/http/javadsl/HttpServerMinimalExampleTest.java) { #minimal-routing-example }

When you run this server, you can either open the page in a browser,
at the following url: [http://localhost:8080/hello](http://localhost:8080/hello), or call it in your terminal, via `curl http://localhost:8080/hello`.

## Marshalling

Transforming request and response bodies between over-the-wire formats and objects to be used in your application is
done separately from the route declarations, in marshallers, which are pulled in implicitly using the "magnet" pattern.
This means that you can `complete` a request with any kind of object as long as there is an implicit marshaller
available in scope.

@@@ div { .group-scala }
Default marshallers are provided for simple objects like String or ByteString, and you can define your own for example
for JSON. An additional module provides JSON serialization using the spray-json library (see @ref[JSON Support](common/json-support.md)
for details):

@@dependency [sbt,Gradle,Maven] {
  symbol="AkkaHttpVersion"
  value="$project.version$"
  group="com.typesafe.akka"
  artifact="akka-http-spray-json_$scala.binary.version$"
  version="AkkaHttpVersion"
}

@@@
@@@ div { .group-java }
JSON support is possible in `akka-http` by the use of Jackson, an external artifact (see @ref[JSON Support](common/json-support.md#jackson-support)
for details):

@@dependency [sbt,Gradle,Maven] {
  symbol="AkkaHttpVersion"
  value="$project.version$"
  group="com.typesafe.akka"
  artifact="akka-http-jackson_$scala.binary.version$"
  version="AkkaHttpVersion"
}

@@@

A common use case is to reply to a request using a model object having the marshaller transform it into JSON. In
this case shown by two separate routes. The first route queries an asynchronous database and marshalls the
@scala[`Future[Option[Item]]`]@java[`CompletionStage<Optional<Item>>`] result into a JSON response. The second unmarshalls an `Order` from the incoming request,
saves it to the database and replies with an OK when done.

Scala
:   @@snip [SprayJsonExample2.scala]($test$/scala/docs/http/scaladsl/SprayJsonExample.scala)

Java
:   @@snip [JacksonExampleTest.java]($test$/java/docs/http/javadsl/JacksonExampleTest.java) { #second-jackson-example }

When you run this server, you can update the inventory via `curl -H "Content-Type: application/json" -X POST -d '{"items":[{"name":"hhgtg","id":42}]}' http://localhost:8080/create-order` on your terminal - adding an item named `"hhgtg"` and having an `id=42`; and then view the inventory either in a browser, at a url like: [http://localhost:8080/item/42](http://localhost:8080/item/42) - or on the terminal,
via `curl http://localhost:8080/item/42`.

The logic for the marshalling and unmarshalling JSON in this example is provided by the @scala["spray-json"]@java["Jackson"] library.
See @scala[@ref[JSON Support](common/json-support.md))]@java[@ref[JSON Support](common/json-support.md#jackson-support))] for more information about integration
with this library.

## Streaming

One of the strengths of Akka HTTP is that streaming data is at its heart meaning that both request and response bodies
can be streamed through the server achieving constant memory usage even for very large requests or responses. Streaming
responses will be backpressured by the remote client so that the server will not push data faster than the client can
handle, streaming requests means that the server decides how fast the remote client can push the data of the request
body.

Example that streams random numbers as long as the client accepts them:

Scala
:   @@snip [HttpServerExampleSpec.scala]($test$/scala/docs/http/scaladsl/HttpServerStreamingRandomNumbers.scala)

Java
:   @@snip [HttpServerStreamRandomNumbersTest.java]($test$/java/docs/http/javadsl/HttpServerStreamRandomNumbersTest.java) { #stream-random-numbers }

Connecting to this service with a slow HTTP client would backpressure so that the next random number is produced on
demand with constant memory usage on the server. This can be seen using curl and limiting the rate
`curl --limit-rate 50b 127.0.0.1:8080/random`

Akka HTTP routes easily interact with actors. In this example one route allows for placing bids in a fire-and-forget
style while the second route contains a request-response interaction with an actor. The resulting response is rendered
as json and returned when the response arrives from the actor.

Scala
:   @@snip [HttpServerExampleSpec.scala]($test$/scala/docs/http/scaladsl/HttpServerWithActorInteraction.scala)

Java
:   @@snip [HttpServerActorInteractionExample.java]($test$/java/docs/http/javadsl/HttpServerActorInteractionExample.java) { #actor-interaction }

When you run this server, you can add an auction bid via `curl -X PUT "http://localhost:8080/auction?bid=22&user=MartinO"` on the terminal; and then you can view the auction status either in a browser, at the url [http://localhost:8080/auction](http://localhost:8080/auction), or, on the terminal, via `curl http://localhost:8080/auction`.

More details on how JSON marshalling and unmarshalling works can be found in the @ref[JSON Support section](common/json-support.md).

Read more about the details of the high level APIs in the section @ref[High-level Server-Side API](routing-dsl/index.md).

## Low-level HTTP server APIs

The low-level Akka HTTP server APIs allows for handling connections or individual requests by accepting
@apidoc[HttpRequest] s and answering them by producing @apidoc[HttpResponse] s. This is provided by the `akka-http-core` module,
which is included automatically when you depend on `akka-http` but can also be used on its own.
APIs for handling such request-responses as function calls and as a @apidoc[Flow[HttpRequest, HttpResponse, \_]] are available.

Scala
:   @@snip [HttpServerLowLevel.scala]($test$/scala/docs/http/scaladsl/HttpServerLowLevel.scala)

Java
:   @@snip [HttpServerLowLevelExample.java]($test$/java/docs/http/javadsl/HttpServerLowLevelExample.java) { #low-level-server-example }

Read more details about the low level APIs in the section @ref[Core Server API](server-side/low-level-api.md).

## HTTP Client API

The client APIs provide methods for calling a HTTP server using the same @apidoc[HttpRequest] and @apidoc[HttpResponse] abstractions
that Akka HTTP server uses but adds the concept of connection pools to allow multiple requests to the same server to be
handled more performantly by re-using TCP connections to the server.

Example simple request:

Scala
:   @@snip [HttpClientExampleSpec.scala]($test$/scala/docs/http/scaladsl/HttpClientSingleRequest.scala)

Java
:   @@snip [ClientSingleRequestExample.java]($test$/java/docs/http/javadsl/ClientSingleRequestExample.java) { #single-request-example }

Read more about the details of the client APIs in the section @ref[Consuming HTTP-based Services (Client-Side)](client-side/index.md).

## The modules that make up Akka HTTP

Akka HTTP is structured into several modules:

akka-http
: Higher-level functionality, like (un)marshalling, (de)compression as well as a powerful DSL
for defining HTTP-based APIs on the server-side, this is the recommended way to write HTTP servers
with Akka HTTP. Details can be found in the section @ref[High-level Server-Side API](routing-dsl/index.md)

akka-http-core
: A complete, mostly low-level, server- and client-side implementation of HTTP (incl. WebSockets)
Details can be found in sections @ref[Core Server API](server-side/low-level-api.md) and @ref[Consuming HTTP-based Services (Client-Side)](client-side/index.md)

akka-http-testkit
: A test harness and set of utilities for verifying server-side service implementations

akka-http2-support
: The HTTP/2 implementation to be included only if @ref[HTTP/2 support](server-side/http2.md) is needed.


@@@ div { .group-scala }
akka-http-spray-json
: Predefined glue-code for (de)serializing custom types from/to JSON with [spray-json](https://github.com/spray/spray-json)
Details can be found here: @ref[JSON Support](common/json-support.md)
@@@

@@@ div { .group-scala }
akka-http-xml
: Predefined glue-code for (de)serializing custom types from/to XML with [scala-xml](https://github.com/scala/scala-xml)
Details can be found here: @ref[XML Support](common/xml-support.md)
@@@
@@@ div { .group-java }
akka-http-jackson
: Predefined glue-code for (de)serializing custom types from/to JSON with [jackson](https://github.com/FasterXML/jackson)
@@@
