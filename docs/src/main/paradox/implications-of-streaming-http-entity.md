# Implications of the streaming nature of Request/Response Entities

Akka HTTP is streaming *all the way through*, which means that the back-pressure mechanisms enabled by Akka Streams
are exposed through all layers–from the TCP layer, through the HTTP server, all the way up to the user-facing @apidoc[HttpRequest]
and @apidoc[HttpResponse] and their @apidoc[HttpEntity] APIs.

This has surprising implications if you are used to non-streaming / not-reactive HTTP clients.
Specifically it means that: "*lack of consumption of the HTTP Entity, is signaled as back-pressure to the other
side of the connection*". This is a feature, as it allows one only to consume the entity, and back-pressure servers/clients
from overwhelming our application, possibly causing unnecessary buffering of the entity in memory.

Put another way: Streaming *all the way through* is a feature of Akka HTTP that allows consuming 
entities (and pulling them through the network) in a streaming fashion, and only *on demand* when the client is 
ready to consume the bytes. Therefore, you have to explicitly consume or discard the entity. 

On a client, for example, if the application doesn't subscribe to the response entity within 
`akka.http.host-connection-pool.response-entity-subscription-timeout`, the stream will fail with a 
`TimeoutException: Response entity was not subscribed after ...`.

@@@ warning

Consuming (or discarding) the Entity of a request is mandatory!
If *accidentally* left neither consumed or discarded Akka HTTP will
assume the incoming data should remain back-pressured, and will stall the incoming data via TCP back-pressure mechanisms.
A client should consume the Entity regardless of the status of the @apidoc[HttpResponse].

@@@

## Client-Side handling of streaming HTTP Entities

### Consuming the HTTP Response Entity (Client)

There are two use-cases to consume the entity of a response:

1. process the bytes as the response arrives from the network buffer
2. load all the bytes in memory first, and process them afterwards

The most common use-case, and recommended, of course, is consuming the response entity as a stream, 
which can be done via running the underlying `dataBytes` Source.

It is encouraged to use various streaming techniques to utilise the underlying infrastructure to its fullest,
for example by framing the incoming chunks, parsing them line-by-line and then connecting the flow into another
destination Sink, such as a File or other Akka Streams connector:

Scala
:   @@snip [HttpClientExampleSpec.scala](/docs/src/test/scala/docs/http/scaladsl/HttpClientExampleSpec.scala) { #manual-entity-consume-example-1 }

Java
:   @@snip [HttpClientExampleDocTest.java](/docs/src/test/java/docs/http/javadsl/HttpClientExampleDocTest.java) { #manual-entity-consume-example-1 }

However, sometimes the need may arise to consume the entire entity as `Strict` entity (which means that it is
completely loaded into memory). Akka HTTP provides a special @scala[`toStrict(timeout)`]@java[`toStrict(timeout, materializer)`] method which can be used to
eagerly consume the entity and make it available in memory. Once in memory, data can be consumed as a `ByteString` or as a `Source`:

Scala
:   @@snip [HttpClientExampleSpec.scala](/docs/src/test/scala/docs/http/scaladsl/HttpClientExampleSpec.scala) { #manual-entity-consume-example-2 }

Java
:   @@snip [HttpClientExampleDocTest.java](/docs/src/test/java/docs/http/javadsl/HttpClientExampleDocTest.java) { #manual-entity-consume-example-2 }

### Integrating with Akka Streams

In some cases, it is necessary to process the results of a series of Akka HTTP calls as Akka Streams. In order
to ensure that the HTTP Response Entity is consumed in a timely manner, the Akka HTTP stream for each request must
be executed and completely consumed, then sent along for further processing.

Failing to account for this behavior can result in seemingly non-deterministic failures due to complex interactions
between http and stream buffering. This manifests as errors such as the following:

```
Response entity was not subscribed after 1 second. Make sure to read the response `entity` body or call `entity.discardBytes()` on it -- in case you deal with `HttpResponse`, use the shortcut `response.discardEntityBytes()`.
```

This error indicates that the http response has been available for too long without being consumed. It can be 
partially worked around by increasing the subscription timeout, but you will still run the risk of running into network 
level timeouts and could still exceed the timeout under load so it's best to resolve the issue properly such as in 
the examples below: 

Scala
:   @@snip [HttpClientExampleSpec.scala](/docs/src/test/scala/docs/http/scaladsl/HttpClientExampleSpec.scala) { #manual-entity-consume-example-3 }

Java
:   @@snip [HttpClientExampleDocTest.java](/docs/src/test/java/docs/http/javadsl/HttpClientExampleDocTest.java) { #manual-entity-consume-example-3 }

### Discarding the HTTP Response Entity (Client)

Sometimes when calling HTTP services we do not care about their response payload (e.g. all we care about is the response code),
yet as explained above entity still has to be consumed in some way, otherwise we'll be exerting back-pressure on the
underlying TCP connection.

The `discardEntityBytes` convenience method serves the purpose of easily discarding the entity if it has no purpose for us.
It does so by piping the incoming bytes directly into an `Sink.ignore`.

The two snippets below are equivalent, and work the same way on the server-side for incoming HTTP Requests:

Scala
:   @@snip [HttpClientExampleSpec.scala](/docs/src/test/scala/docs/http/scaladsl/HttpClientExampleSpec.scala) { #manual-entity-discard-example-1 }

Java
:   @@snip [HttpClientExampleDocTest.java](/docs/src/test/java/docs/http/javadsl/HttpClientExampleDocTest.java) { #manual-entity-discard-example-1 }

Or the equivalent low-level code achieving the same result:

Scala
:   @@snip [HttpClientExampleSpec.scala](/docs/src/test/scala/docs/http/scaladsl/HttpClientExampleSpec.scala) { #manual-entity-discard-example-2 }

Java
:   @@snip [HttpClientExampleDocTest.java](/docs/src/test/java/docs/http/javadsl/HttpClientExampleDocTest.java) { #manual-entity-discard-example-2 }

## Server-Side handling of streaming HTTP Entities

HTTP Entities of a request are directly linked to Streams fed by the underlying
TCP connection. Thus, if request entities remain not consumed, the server will back-pressure the connection, expecting
the user-code to eventually decide what to do with the incoming data.

The most common use-case is to consume the request entity using directives such as `BasicDirectives.extractDataBytes`. Some 
directives force an implicit `toStrict` operation, such as @scala[`entity(as[String])`]@java[`entity(exampleUnmarshaller, example -> {})`].

### Consuming the HTTP Request Entity (Server)

The simplest way of consuming the incoming request entity is to transform it into an actual domain object,
for example by using the @ref[entity](routing-dsl/directives/marshalling-directives/entity.md) directive:

Scala
:   @@snip [HttpServerExampleSpec.scala](/docs/src/test/scala/docs/http/scaladsl/HttpServerExampleSpec.scala) { #consume-entity-directive }

Java
:   @@snip [HttpServerExampleDocTest.java](/docs/src/test/java/docs/http/javadsl/server/HttpServerExampleDocTest.java) { #consume-entity-directive }

You can also access the raw `dataBytes` and run the underlying stream. For example, you could pipe the raw `dataBytes` into a
FileIO `Sink`. The FileIO `Sink` signals completion via a @scala[`Future[IoResult]`]@java[`CompletionStage<IoResult>`] 
once all the data has been written into the file:

Scala
:   @@snip [HttpServerExampleSpec.scala](/docs/src/test/scala/docs/http/scaladsl/HttpServerExampleSpec.scala) { #consume-raw-dataBytes }

Java
:   @@snip [HttpServerExampleDocTest.java](/docs/src/test/java/docs/http/javadsl/server/HttpServerExampleDocTest.java) { #consume-raw-dataBytes }

### Discarding the HTTP Request Entity (Server)

You may want to discard the uploaded entity. For example, depending on some validation (e.g. "is user authorized to upload files?").

Please note that "discarding the HTTP Request Entity" means that the entire upload will proceed, even though you are not interested in the data
being streamed to the server. This is useful if you are simply not interested in the entity. 

In order to discard the `dataBytes` explicitly you can invoke the `discardEntityBytes` bytes of the incoming `HttpRequest`:

Scala
:   @@snip [HttpServerExampleSpec.scala](/docs/src/test/scala/docs/http/scaladsl/HttpServerExampleSpec.scala) { #discard-discardEntityBytes }

Java
:   @@snip [HttpServerExampleDocTest.java](/docs/src/test/java/docs/http/javadsl/server/HttpServerExampleDocTest.java) { #discard-discardEntityBytes }

A related concept is *cancelling* the incoming @scala[`entity.dataBytes`]@java[`entity.getDataBytes()`] stream. Cancellation results in Akka HTTP
*abruptly closing the connection from the Client*. This may be useful when you detect that the given user should not be allowed to make any
uploads at all, and you want to drop the connection (instead of reading and ignoring the incoming data).
This can be done by attaching the incoming @scala[`entity.dataBytes`]@java[`entity.getDataBytes()`] to a `Sink.cancelled()` which will cancel
the entity stream, which in turn will cause the underlying connection to be shut-down by the server –
effectively hard-aborting the incoming request:

Scala
:   @@snip [HttpServerExampleSpec.scala](/docs/src/test/scala/docs/http/scaladsl/HttpServerExampleSpec.scala) { #discard-close-connections }

Java
:   @@snip [HttpServerExampleDocTest.java](/docs/src/test/java/docs/http/javadsl/server/HttpServerExampleDocTest.java) { #discard-close-connections }

See also the @ref[Closing a connection](server-side/low-level-api.md#http-closing-connection-low-level) section for an 
in-depth explanation on closing connection.

### Pending: Automatic discarding of not used entities

Under certain conditions it is possible to detect an entity is very unlikely to be used by the user for a given request,
and issue warnings or discard the entity automatically. This advanced feature has not been implemented yet, see the below
note and issues for further discussion and ideas.

@@@ note

An advanced feature code named "auto draining" has been discussed and proposed for Akka HTTP, and we're hoping
to implement or help the community implement it.

You can read more about it in [issue #183](https://github.com/akka/akka-http/issues/183)
as well as [issue #117](https://github.com/akka/akka-http/issues/117) ; as always, contributions are very welcome!

@@@
