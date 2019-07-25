# Client-Side WebSocket Support

Client side WebSocket support is available through @scala[`Http().singleWebSocketRequest`]@java[`Http.get(system).singleWebSocketRequest`],
@scala[`Http().webSocketClientFlow`]@java[`Http.get(system).webSocketClientFlow`] and @scala[`Http().webSocketClientLayer`]@java[`Http.get(system).webSocketClientLayer`].

A WebSocket consists of two streams of messages, incoming messages (a @apidoc[Sink]) and outgoing messages
(a @apidoc[Source]) where either may be signalled first; or even be the only direction in which messages flow during
the lifetime of the connection. Therefore a WebSocket connection is modelled as either something you connect a
@apidoc[Flow[Message, Message, Mat]] to or a @apidoc[Flow[Message, Message, Mat]] that you connect a @apidoc[Source[Message, Mat]] and
a @apidoc[Sink[Message, Mat]] to.

A WebSocket request starts with a regular HTTP request which contains an `Upgrade` header (and possibly
other regular HTTP request properties), so in addition to the flow of messages there also is an initial response
from the server, this is modelled with @apidoc[WebSocketUpgradeResponse].

The methods of the WebSocket client API handle the upgrade to WebSocket on connection success and materializes
the connected WebSocket stream. If the connection fails, for example with a `404 NotFound` error, this regular
HTTP result can be found in `WebSocketUpgradeResponse.response`

@@@ note
Make sure to read and understand the section about [Half-Closed WebSockets](#half-closed-client-websockets) as the behavior
when using WebSockets for one-way communication may not be what you would expect.
@@@

## Message

Messages sent and received over a WebSocket can be either @apidoc[TextMessage] s or @apidoc[BinaryMessage] s and each
of those has two subtypes `Strict` (all data in one chunk) or `Streamed`. In typical applications messages will be `Strict` as
WebSockets are usually deployed to communicate using small messages not stream data, the protocol does however
allow this (by not marking the first fragment as final, as described in [RFC 6455 section 5.2](https://tools.ietf.org/html/rfc6455#section-5.2)).

The strict text is available from @scala[`TextMessage.Strict`]@java[`TextMessage.getStrictText`] and strict binary data from
@scala[`BinaryMessage.Strict`]@java[`BinaryMessage.getStrictData`].

For streamed messages @scala[`BinaryMessage.Streamed`]@java[`BinaryMessage.getStreamedData`] and @scala[`TextMessage.Streamed`]@java[`TextMessage.getStreamedText`] will be used.
In these cases the data is provided as a @apidoc[Source[ByteString, \_]] for binary and @apidoc[Source[String, \_]] for text messages.

## singleWebSocketRequest

`singleWebSocketRequest` takes a @apidoc[WebSocketRequest] and a flow it will connect to the source and
sink of the WebSocket connection. It will trigger the request right away and returns a tuple containing the
@scala[`Future[WebSocketUpgradeResponse]`]@java[`CompletionStage<WebSocketUpgradeResponse>`] and the materialized value from the flow passed to the method.

The future will succeed when the WebSocket connection has been established or the server returned a regular
HTTP response, or fail if the connection fails with an exception.

Simple example sending a message and printing any incoming message:

Scala
:   @@snip [WebSocketClientExampleSpec.scala]($test$/scala/docs/http/scaladsl/WebSocketClientExampleSpec.scala) { #single-WebSocket-request }

Java
:   @@snip [WebSocketClientExampleTest.java]($test$/java/docs/http/javadsl/WebSocketClientExampleTest.java) { #single-WebSocket-request }

The websocket request may also include additional headers, like in this example, HTTP Basic Auth:

Scala
:   @@snip [WebSocketClientExampleSpec.scala]($test$/scala/docs/http/scaladsl/WebSocketClientExampleSpec.scala) { #authorized-single-WebSocket-request }

Java
:   @@snip [WebSocketClientExampleTest.java]($test$/java/docs/http/javadsl/WebSocketClientExampleTest.java) { #authorized-single-WebSocket-request }

## webSocketClientFlow

`webSocketClientFlow` takes a request, and returns a @scala[@apidoc[Flow[Message, Message, Future[WebSocketUpgradeResponse]]]]@java[@apidoc[Flow[Message, Message, CompletionStage[WebSocketUpgradeResponse]]]].

The future that is materialized from the flow will succeed when the WebSocket connection has been established or
the server returned a regular HTTP response, or fail if the connection fails with an exception.

@@@ note
The @apidoc[Flow] that is returned by this method can only be materialized once. For each request a new
flow must be acquired by calling the method again.
@@@

Simple example sending a message and printing any incoming message:

Scala
:   @@snip [WebSocketClientExampleSpec.scala]($test$/scala/docs/http/scaladsl/WebSocketClientExampleSpec.scala) { #WebSocket-client-flow }

Java
:   @@snip [WebSocketClientExampleTest.java]($test$/java/docs/http/javadsl/WebSocketClientExampleTest.java) { #WebSocket-client-flow }

## webSocketClientLayer

Just like the @ref[Stand-Alone HTTP Layer Usage](connection-level.md#http-client-layer) for regular HTTP requests, the WebSocket layer can be used fully detached from the
underlying TCP interface. The same scenarios as described for regular HTTP requests apply here.

The returned layer forms a @scala[@apidoc[BidiFlow[Message, SslTlsOutbound, SslTlsInbound, Message, Future[WebSocketUpgradeResponse]]]]@java[@apidoc[BidiFlow[Message, SslTlsOutbound, SslTlsInbound, Message, CompletionStage[WebSocketUpgradeResponse]]]].

<a id="half-closed-client-websockets"></a>
## Half-Closed WebSockets

The Akka HTTP WebSocket API does not support half-closed connections which means that if either stream completes the
entire connection is closed (after a "Closing Handshake" has been exchanged or a timeout of 3 seconds has passed).
This may lead to unexpected behavior, for example if we are trying to only consume messages coming from the server,
like this:

Scala
:   @@snip [WebSocketClientExampleSpec.scala]($test$/scala/docs/http/scaladsl/WebSocketClientExampleSpec.scala) { #half-closed-WebSocket-closing-example }

Java
:   @@snip [WebSocketClientExampleTest.java]($test$/java/docs/http/javadsl/WebSocketClientExampleTest.java) { #half-closed-WebSocket-closing }

This will in fact quickly close the connection because of the @scala[`Source.empty`]@java[`Source.empty()`] being completed immediately when the
stream is materialized. To solve this you can make sure to not complete the outgoing source by using for example
@scala[`Source.maybe`]@java[`Source.maybe()`] like this:

Scala
:   @@snip [WebSocketClientExampleSpec.scala]($test$/scala/docs/http/scaladsl/WebSocketClientExampleSpec.scala) { #half-closed-WebSocket-working-example }

Java
:   @@snip [WebSocketClientExampleTest.java]($test$/java/docs/http/javadsl/WebSocketClientExampleTest.java) { #half-closed-WebSocket-working }

This will keep the outgoing source from completing, but without emitting any elements until the @scala[`Promise`]@java[`CompletableFuture`] is manually
completed which makes the @apidoc[Source] complete and the connection to close.

The same problem holds true if emitting a finite number of elements, as soon as the last element is reached the @apidoc[Source]
will close and cause the connection to close. To avoid that you can concatenate @scala[`Source.maybe`]@java[`Source.maybe()`] to the finite stream:

Scala
:   @@snip [WebSocketClientExampleSpec.scala]($test$/scala/docs/http/scaladsl/WebSocketClientExampleSpec.scala) { #half-closed-WebSocket-finite-working-example }

Java
:   @@snip [WebSocketClientExampleTest.java]($test$/java/docs/http/javadsl/WebSocketClientExampleTest.java) { #half-closed-WebSocket-finite }

Scenarios that exist with the two streams in a WebSocket and possible ways to deal with it:

|Scenario                              | Possible solution                                                                                                    |
|--------------------------------------|----------------------------------------------------------------------------------------------------------------------|
|Two-way communication                 | `Flow.fromSinkAndSource`, or `Flow.map` for a request-response protocol                                              |
|Infinite incoming stream, no outgoing | @scala[`Flow.fromSinkAndSource(someSink, Source.maybe)`]@java[`Flow.fromSinkAndSource(someSink, Source.maybe())`]    |
|Infinite outgoing stream, no incoming | @scala[`Flow.fromSinkAndSource(Sink.ignore, yourSource)`]@java[`Flow.fromSinkAndSource(Sink.ignore(), yourSource)`]|

<a id="keep-alive-ping"></a>

## Automatic keep-alive Ping support

Similar to the @ref[server-side kee-alive Ping support](../server-side/websocket-support.md#keep-alive-ping),
it is possible to configure the client-side to perform automatic keep-alive using Ping (or Pong) frames.

This is supported in a transparent way via configuration by setting the: 
`akka.http.client.websocket.periodic-keep-alive-max-idle = 1 second` to a specified max idle timeout. The keep alive triggers
when no other messages are in-flight during the such configured period. Akka HTTP will then automatically send
a [`Ping` frame](https://tools.ietf.org/html/rfc6455#section-5.5.2) for each of such idle intervals.

By default, the automatic keep-alive feature is disabled.

### Custom keep-alive data payloads

By default, pings do not carry any payload, as it is often enough to simply push *any* frame over the connection
to ensure the connection stays healthy (or detect if it was severed), however you may configure them to carry a custom 
payload, to do this you can provide a function that will be asked to emit the payload for each of the ping messages generated:

Scala
:  @@snip [WebSocketExampleSpec.scala]($test$/scala/docs/http/scaladsl/server/WebSocketExampleSpec.scala) { #websocket-client-ping-payload }

Java
:  @@snip [WebSocketCoreExample.java]($test$/java/docs/http/javadsl/server/WebSocketCoreExample.java) { #websocket-client-ping-payload }

### Uni-directional Pong keep-alive


A Ping response will always be replied to by the client-side with an appropriate `Pong` reply, carrying the same payload.
It is also possible to configure the keep-alive mechanism to send `Pong` frames instead of `Ping` frames, 
which enables an [uni-directional heartbeat](https://tools.ietf.org/html/rfc6455#section-5.5.3) mechanism (in which case 
the client side will *not* reply to such heartbeat). You can configure this mode by setting: 
`akka.http.client.websocket.periodic-keep-alive-mode = pong`.
