# 4. Server API

Akka HTTP also provides an embedded,
[Reactive-Streams](https://www.reactive-streams.org/)-based, fully asynchronous HTTP/1.1 server implemented on top of @extref[Streams](akka-docs:stream/index.html).

It supports the following features:

 * Full support for [HTTP persistent connections](https://en.wikipedia.org/wiki/HTTP_persistent_connection)
 * Full support for asynchronous HTTP streaming including "chunked" transfer encoding accessible through an idiomatic API
 * @ref[WebSocket support](websocket-support.md)
 * Optional @ref[SSL/TLS encryption](server-https-support.md)
 * Optional support for [HTTP pipelining](https://en.wikipedia.org/wiki/HTTP_pipelining)

The server-side components of Akka HTTP are split into two layers:

@ref[High-level Server-Side API](../routing-dsl/index.md)
:  Higher-level functionality in the `akka-http` module which offers a very flexible "Routing DSL" for elegantly defining RESTful web services as well as
   functionality of typical web servers or frameworks, like deconstruction of URIs, content negotiation or static content serving.

@ref[Core Server API](low-level-api.md)
:  The basic low-level server implementation in the `akka-http-core` module.

Depending on your needs you can either use the low-level API directly or rely on the high-level
@ref[Routing DSL](../routing-dsl/index.md) which can make the definition of more complex service logic much
easier. You can also interact with different API levels at the same time and, independently of which API level you choose
Akka HTTP will happily serve many thousand concurrent connections to a single or many clients.

@@@ note
It is recommended to read the @ref[Implications of the streaming nature of Request/Response Entities](../implications-of-streaming-http-entity.md) section,
as it explains the underlying full-stack streaming concepts, which may be unexpected when coming from a background with non-"streaming first" HTTP Servers.
@@@

@@toc { depth=2 }

@@@ index

* [routing-dsl/index](../routing-dsl/index.md)
* [low-level-api](low-level-api.md)
* [websocket-support](websocket-support.md)
* [server-https-support](server-https-support.md)
* [graceful-termination](graceful-termination.md)
* [http2](http2.md)

@@@
