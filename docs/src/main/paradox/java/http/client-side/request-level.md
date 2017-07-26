# Request-Level Client-Side API

The request-level API is the recommended and most convenient way of using Akka HTTP's client-side functionality. It internally builds upon the
@ref[Host-Level Client-Side API](host-level.md) to provide you with a simple and easy-to-use way of retrieving HTTP responses from remote servers.
Depending on your preference you can pick the flow-based or the future-based variant.

@@@ note
The request-level API is implemented on top of a connection pool that is shared inside the ActorSystem. A consequence of
using a pool is that long-running requests block a connection while running and starve other requests. Make sure not to use
the request-level API for long-running requests like long-polling GET requests. Use the @ref[Connection-Level Client-Side API](connection-level.md#connection-level-api-java)
or an extra pool just for the long-running connection instead.
@@@

## Future-Based Variant

Most often your HTTP client needs are very basic. You simply need the HTTP response for a certain request and don't
want to bother with setting up a full-blown streaming infrastructure.

For these cases Akka HTTP offers the `Http().singleRequest(...)` method, which simply turns an `HttpRequest` instance
into `CompletionStage<HttpResponse>`. Internally the request is dispatched across the (cached) host connection pool for the
request's effective URI.

Just like in the case of the super-pool flow described above the request must have either an absolute URI or a valid
`Host` header, otherwise the returned future will be completed with an error.

@@snip [HttpClientExampleDocTest.java](../../../../../test/java/docs/http/javadsl/HttpClientExampleDocTest.java) { #single-request-example }

### Using the Future-Based API in Actors

When using the `CompletionStage` based API from inside an `Actor`, all the usual caveats apply to how one should deal
with the futures completion. For example you should not access the Actors state from within the CompletionStage's callbacks
(such as `map`, `onComplete`, ...) and instead you should use the `pipe` pattern to pipe the result back
to the Actor as a message:

```java
class Myself extends AbstractActor {
  final Http http = Http.get(context().system());
  final ExecutionContextExecutor dispatcher = context().dispatcher();
  final Materializer materializer = ActorMaterializer.create(context());

  public Myself() { // syntax changes slightly in Akka 2.5, see the migration guide
    receive(ReceiveBuilder
     .match(String.class, url -> {
       pipe(fetch (url), dispatcher).to(self());
     }).build());
  }

  CompletionStage<HttpResponse> fetch(String url) {
    return http.singleRequest(HttpRequest.create(url), materializer);
  }
}
```

@@@ warning

Be sure to consume the response entities `dataBytes:Source[ByteString,Unit]` by for example connecting it
to a `Sink` (for example `response.discardEntityBytes(Materializer)` if you don't care about the
response entity), since otherwise Akka HTTP (and the underlying Streams infrastructure) will understand the
lack of entity consumption as a back-pressure signal and stop reading from the underlying TCP connection!

This is a feature of Akka HTTP that allows consuming entities (and pulling them through the network) in
a streaming fashion, and only *on demand* when the client is ready to consume the bytes -
it may be a bit surprising at first though.

There are tickets open about automatically dropping entities if not consumed ([#183](https://github.com/akka/akka-http/issues/183) and [#117](https://github.com/akka/akka-http/issues/117)),
so these may be implemented in the near future.

@@@

## Flow-Based Variant

The flow-based variant of the request-level client-side API is presented by the `Http().superPool(...)` method.
It creates a new "super connection pool flow", which routes incoming requests to a (cached) host connection pool
depending on their respective effective URIs.

The `Flow` returned by `Http().superPool(...)` is very similar to the one from the @ref[Host-Level Client-Side API](host-level.md), so the
@ref[Using a Host Connection Pool](host-level.md#using-a-host-connection-pool-java) section also applies here.

However, there is one notable difference between a "host connection pool client flow" for the host-level API and a
"super-pool flow":
Since in the former case the flow has an implicit target host context the requests it takes don't need to have absolute
URIs or a valid `Host` header. The host connection pool will automatically add a `Host` header if required.

For a super-pool flow this is not the case. All requests to a super-pool must either have an absolute URI or a valid
`Host` header, because otherwise it'd be impossible to find out which target endpoint to direct the request to.

