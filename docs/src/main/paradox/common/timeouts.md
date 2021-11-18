# Timeouts

Akka HTTP comes with a variety of built-in timeout mechanisms to protect your servers from malicious attacks or
programming mistakes. Some of these are simply configuration options (which may be overridden in code) while others
are left to the streaming APIs and are easily implementable as patterns in user-code directly.

## Common timeouts

<a id="idle-timeouts"></a>
### Connection-level idle timeout

The `idle-timeout` is a setting which sets the maximum inactivity time of a given connection. If no data is sent or received
on a connection for over `idle-timeout` time, the connection will be automatically closed.

This setting should be used as a last-resort safeguard to prevent unused or stuck connections from consuming resources for
an indefinite time.

The setting works the same way for server and client connections and it is configurable independently using the following keys:

```
akka.http.server.idle-timeout
akka.http.client.idle-timeout
akka.http.host-connection-pool.client.idle-timeout
```

## Server timeouts

<a id="request-timeout"></a>
### Request timeout

Request timeouts are a mechanism that limits the maximum time it may take to produce an @apidoc[HttpResponse] from a route.
If that deadline is not met the server will automatically inject a Service Unavailable HTTP response and close the connection
to prevent it from leaking and staying around indefinitely (for example if by programming error a Future would never complete,
never sending the real response otherwise).

The default @apidoc[HttpResponse] that is written when a request timeout is exceeded looks like this:

@@snip [HttpServerBluePrint.scala]($akka-http$/akka-http-core/src/main/scala/akka/http/impl/engine/server/HttpServerBluePrint.scala) { #default-request-timeout-httpresponse }

A default request timeout is applied globally to all routes and can be configured using the
`akka.http.server.request-timeout` setting (which defaults to 20 seconds).

The request timeout can be configured at run-time for a given route using the any of the @ref[TimeoutDirectives](../routing-dsl/directives/timeout-directives/index.md).

### Bind timeout

The bind timeout is the time period within which the TCP binding process must be completed (using any of the `Http().bind*` methods).
It can be configured using the `akka.http.server.bind-timeout` setting.

### Linger timeout

The linger timeout is the time period the HTTP server implementation will keep a connection open after
all data has been delivered to the network layer. This setting is similar to the SO_LINGER socket option
but does not only include the OS-level socket but also covers the Akka IO / Akka Streams network stack.
The setting is an extra precaution that prevents clients from keeping open a connection that is
already considered completed from the server side.

If the network level buffers (including the Akka Stream / Akka IO networking stack buffers)
contains more data than can be transferred to the client in the given time when the server-side considers
to be finished with this connection, the client may encounter a connection reset.

Set to `infinite` to disable automatic connection closure (which will risk to leak connections).

## Client timeouts

### Connecting timeout

The connecting timeout is the time period within which the TCP connecting process must be completed.
Tweaking it should rarely be required, but it allows erroring out the connection in case a connection
is unable to be established for a given amount of time.

It can be configured using the `akka.http.client.connecting-timeout` setting.

## Client pool timeouts

### Keep-alive timeout

HTTP connections are commonly used for multiple requests, that is, they are kept alive between requests. The
`akka.http.host-connection-pool.keep-alive-timeout` setting configures how long a pool keeps a connection alive between
requests before it closes the connection (and eventually reestablishes it).

A common scenario where this setting is useful is to prevent a race-condition inherent in HTTP: in most cases, a server
or reverse-proxy closes a persistent (kept-alive) connection after some time. HTTP does not define a protocol between
client and server to negotiate a graceful teardown of an idle persistent connection. Therefore, it can happen that a server decides to
close a connection at the same time that a client decides to send a new request. In that case, the request will fail to be processed,
but the client cannot determine for which reason the server closed the connection and whether the request was (partly) processed or not.
Such a condition can be observed when a request fails with an `UnexpectedConnectionClosureException` or a `StreamTcpException` stating
"Connection reset by peer".

To prevent this from happening, you can set the timeout to a lower value than the server-side keep-alive timeout
(which you either have to know or find out experimentally).

Set to `infinite` to allow the connection to remain open indefinitely (or be closed by the more general `idle-timeout`).

### Connection Lifetime timeout

This timeout configures a maximum amount of time, while the connection can be kept open. This is useful, when you reach
the server through a load balancer and client reconnecting helps the process of rebalancing between service instances.

It can be configured using the `akka.http.host-connection-pool.max-connection-lifetime` setting.

### Pool Idle timeout

A connection pool to a target host will be shut down after the timeout given as `akka.http.host-connection-pool.idle-timeout`. This frees
resources like open but idle pool connections and management structures.

If the application connects to only a limited set of target hosts over its lifetime and resource usage for the pool is of no concern, the
`idle-timeout` can be completely disabled by setting it to `infinite`.

A pool will be automatically reestablished when a new request comes in for a target host.
