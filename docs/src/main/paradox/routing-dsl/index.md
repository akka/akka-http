# Routing DSL

In addition to the @ref[Core Server API](../server-side/low-level-api.md) Akka HTTP provides a very flexible "Routing DSL" for elegantly
defining RESTful web services. It picks up where the low-level API leaves off and offers much of the higher-level
functionality of typical web servers or frameworks, like deconstruction of URIs, content negotiation or
static content serving.

@@@ note
It is recommended to read the @ref[Implications of the streaming nature of Request/Response Entities](../implications-of-streaming-http-entity.md) section,
as it explains the underlying full-stack streaming concepts, which may be unexpected when coming
from a background with non-"streaming first" HTTP Servers.
@@@

@@toc { depth=1 }

@@@ index

* [overview](overview.md)
* [routes](routes.md)
* [directives/index](directives/index.md)
* [rejections](rejections.md)
* [exception-handling](exception-handling.md)
* [path-matchers](path-matchers.md)
* [case-class-extraction](case-class-extraction.md)
* [source-streaming-support](source-streaming-support.md)
* [testkit](testkit.md)
* [http-app](HttpApp.md)

@@@

## Minimal Example

This is a complete, very basic Akka HTTP application relying on the Routing DSL:

Scala
:  @@snip [HttpServerExampleSpec.scala]($test$/scala/docs/http/scaladsl/HttpServerExampleSpec.scala) { #minimal-routing-example }

Java
:  @@snip [HttpServerMinimalExampleTest.java]($test$/java/docs/http/javadsl/HttpServerMinimalExampleTest.java) { #minimal-routing-example }

It starts an HTTP Server on localhost and replies to GET requests to `/hello` with a simple response.

@@@ warning { title="API may change" }
The following example uses an experimental feature and its API is subjected to change in future releases of Akka HTTP.
For further information about this marker, see @extref:[The @DoNotInherit and @ApiMayChange markers](akka-docs:common/binary-compatibility-rules.html#the-donotinherit-and-apimaychange-markers)
in the Akka documentation.
@@@

To help start a server Akka HTTP provides an experimental helper class called @apidoc[HttpApp].
This is the same example as before rewritten using @apidoc[HttpApp]:

Scala
:  @@snip [HttpAppExampleSpec.scala]($test$/scala/docs/http/scaladsl/HttpAppExampleSpec.scala) { #minimal-routing-example }

Java
:  @@snip [HttpAppExampleTest.java]($test$/java/docs/http/javadsl/server/HttpAppExampleTest.java) { #minimal-routing-example }

See @ref[HttpApp Bootstrap](HttpApp.md) for more details about setting up a server using this approach.

@@@ div { .group-scala }

## Longer Example

The following is an Akka HTTP route definition that tries to show off a few features. The resulting service does
not really do anything useful but its definition should give you a feel for what an actual API definition with
the Routing DSL will look like:

@@snip [HttpServerExampleSpec.scala]($test$/scala/docs/http/scaladsl/HttpServerExampleSpec.scala) { #long-routing-example }

@@@

## Interaction with Akka Typed

Since Akka version `2.5.22`, Akka typed became ready for production, Akka HTTP, however, is still using the
untyped `ActorSystem`. This following example will demonstrate how to use Akka HTTP and Akka Typed together
within the same application.

We will create a small web server responsible to record build jobs with its state and duration, query jobs by
id and status, and clear the job history.

First let's start by defining the `Behavior` that will act as a repository for the build job information, this isn't 
strictly needed for our sample but just to have an actual actor to interact with:

Scala
:  @@snip [HttpServerWithTypedSample.scala]($test$/scala/docs/http/scaladsl/HttpServerWithTypedSample.scala) { #akka-typed-behavior }


Then, let's define the JSON marshaller and unmarshallers for the HTTP routes:

Scala
:  @@snip [HttpServerWithTypedSample.scala]($test$/scala/docs/http/scaladsl/HttpServerWithTypedSample.scala) { #akka-typed-json }


Next step is to define the @apidoc[Route$] that will communicate with the previously defined behavior
and handle all its possible responses:

Scala
:  @@snip [HttpServerWithTypedSample.scala]($test$/scala/docs/http/scaladsl/HttpServerWithTypedSample.scala) { #akka-typed-route }


Finally, we create a `Behavior` that bootstraps the web server and use it as the root behavior of our actor system:

Scala
:  @@snip [HttpServerWithTypedSample.scala]($test$/scala/docs/http/scaladsl/HttpServerWithTypedSample.scala) { #akka-typed-bootstrap }


Note that the `akka.actor.typed.ActorSystem` is converted with `toClassic`, which comes from
`import akka.actor.typed.scaladsl.adapter._`. If you are using an earlier version than Akka 2.5.26 this conversion method is named `toUntyped`.

## Dynamic Routing Example

As the routes are evaluated for each request, it is possible to make changes at runtime. Please note that every access
may happen on a separated thread, so any shared mutable state must be thread safe.

The following is an Akka HTTP route definition that allows dynamically adding new or updating mock endpoints with
associated request-response pairs at runtime.

Scala
:  @@snip [HttpServerExampleSpec.scala]($test$/scala/docs/http/scaladsl/HttpServerExampleSpec.scala) { #dynamic-routing-example }

Java
:  @@snip [HttpServerDynamicRoutingExampleTest.java]($test$/java/docs/http/javadsl/HttpServerDynamicRoutingExampleTest.java) { #dynamic-routing-example }

For example, let's say we do a POST request with body:

```json
{
  "path": "test",
  "requests": [
    {"id": 1},
    {"id": 2}
  ],
  "responses": [
    {"amount": 1000},
    {"amount": 2000}
  ]
}
```

Subsequent POST request to `/test` with body `{"id": 1}` will be responded with `{"amount": 1000}`.

## Handling HTTP Server failures in the High-Level API

There are various situations when failure may occur while initialising or running an Akka HTTP server.
Akka by default will log all these failures, however sometimes one may want to react to failures in addition
to them just being logged, for example by shutting down the actor system, or notifying some external monitoring
end-point explicitly.

### Bind failures

For example the server might be unable to bind to the given port. For example when the port
is already taken by another application, or if the port is privileged (i.e. only usable by `root`).
In this case the "binding future" will fail immediately, and we can react to it by listening on the @scala[`Future`]@java[`CompletionStage`]'s completion:

Scala
:  @@snip [HttpServerExampleSpec.scala]($test$/scala/docs/http/scaladsl/HttpServerExampleSpec.scala) { #binding-failure-high-level-example }

Java
:  @@snip [HighLevelServerBindFailureExample.java]($test$/java/docs/http/javadsl/server/HighLevelServerBindFailureExample.java) { #binding-failure-high-level-example }

@@@ note
For a more low-level overview of the kinds of failures that can happen and also more fine-grained control over them
refer to the @ref[Handling HTTP Server failures in the Low-Level API](../server-side/low-level-api.md#handling-http-server-failures-low-level) documentation.
@@@

### Failures and exceptions inside the Routing DSL

Exception handling within the Routing DSL is done by providing @apidoc[ExceptionHandler] s which are documented in-depth
in the @ref[Exception Handling](exception-handling.md) section of the documentation. You can use them to transform exceptions into
@apidoc[HttpResponse] s with appropriate error codes and human-readable failure descriptions.

## File uploads

For high level directives to handle uploads see the @ref[FileUploadDirectives](directives/file-upload-directives/index.md).

Handling a simple file upload from for example a browser form with a *file* input can be done
by accepting a *Multipart.FormData* entity, note that the body parts are *Source* rather than
all available right away, and so is the individual body part payload so you will need to consume
those streams both for the file and for the form fields.

Here is a simple example which just dumps the uploaded file into a temporary file on disk, collects
some form fields and saves an entry to a fictive database:

Scala
:  @@snip [FileUploadExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/FileUploadExamplesSpec.scala) { #simple-upload }

Java
:  @@snip [FileUploadExamplesTest.java]($test$/java/docs/http/javadsl/server/FileUploadExamplesTest.java) { #simple-upload }

You can transform the uploaded files as they arrive rather than storing them in a temporary file as
in the previous example. In this example we accept any number of `.csv` files, parse those into lines
and split each line before we send it to an actor for further processing:

Scala
:  @@snip [FileUploadExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/FileUploadExamplesSpec.scala) { #stream-csv-upload }

Java
:  @@snip [FileUploadExamplesTest.java]($test$/java/docs/http/javadsl/server/FileUploadExamplesTest.java) { #stream-csv-upload }

## Configuring Server-side HTTPS

For detailed documentation about configuring and using HTTPS on the server-side refer to @ref[Server-Side HTTPS Support](../server-side/server-https-support.md).
