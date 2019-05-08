# Pluggable Client Transports / HTTP(S) proxy Support

The client side infrastructure has support to plug different transport mechanisms underneath (the API may still change in the future). A client side
transport is represented by an instance of
@scala[@scaladoc[akka.http.scaladsl.ClientTransport](akka.http.scaladsl.ClientTransport)]@java[@javadoc[akka.http.javadsl.ClientTransport](akka.http.javadsl.ClientTransport)]:

Scala
:  @@snip [ClientTransport.scala]($akka-http$/akka-http-core/src/main/scala/akka/http/scaladsl/ClientTransport.scala) { #client-transport-definition }

Java
:  @@snip [ClientTransport.scala]($akka-http$/akka-http-core/src/main/scala/akka/http/javadsl/ClientTransport.scala) { #client-transport-definition }

A transport implementation defines how the client infrastructure should communicate with a given host.

@@@note

In our model, SSL/TLS runs on top of the client transport, even if you could theoretically see it as part of the
transport layer itself.

@@@

## Configuring Client Transports

A @apidoc[ClientTransport] can be configured in the @apidoc[ClientConnectionSettings]. Right now, this is not possible
through config files but only by code. First, use `ClientConnectionSettings.withTransport` to configure a transport,
then use `ConnectionPoolSettings.withConnectionSettings`. @apidoc[ClientConnectionSettings] can be passed to all
client-side entry points in @scala[@apidoc[Http$]]@java[@apidoc[Http]].

## Predefined Transports

### TCP

The default transport is `ClientTransport.TCP` which simply opens a TCP connection to the target host.

### HTTP(S) Proxy

A transport that connects to target servers via an HTTP(S) proxy. An HTTP(S) proxy uses the HTTP `CONNECT` method (as
specified in [RFC 7231 Section 4.3.6](https://tools.ietf.org/html/rfc7231#section-4.3.6)) to create tunnels to target
servers. The proxy itself should transparently forward data to the target servers so that end-to-end encryption should
still work (if TLS breaks, then the proxy might be fussing with your data).

This approach is commonly used to securely proxy requests to HTTPS endpoints. In theory it could also be used to proxy
requests targeting HTTP endpoints, but we have not yet found a proxy that in fact allows this.

Instantiate the HTTP(S) proxy transport using `ClientTransport.httpsProxy(proxyAddress)`.

The proxy transport can also be setup using `ClientTransport.httpsProxy()` or `ClientTransport.httpsProxy(basicHttpCredentials)`
In order to defined the transport as such, you will need to set the proxy host / port in your `conf` file like the following.

```
akka.http.client.proxy {
 https {
   host = ""
   port = 443
 }
}
```

If host is left as `""` and you attempt to setup a httpsProxy transport, an exception will be thrown.

### Use HTTP(S) proxy with @scala[`Http().singleRequest`]@java[`Http.get(...).singleRequest`]

To make use of an HTTP proxy when using the `singleRequest` API you simply need to configure the proxy and pass
the appropriate settings object when calling the single request method.

Scala
:  @@snip [HttpClientExampleSpec.scala]($test$/scala/docs/http/scaladsl/HttpClientExampleSpec.scala) { #https-proxy-example-single-request }

Java
:  @@snip [HttpClientExampleDocTest.java]($test$/java/docs/http/javadsl/HttpClientExampleDocTest.java) { #https-proxy-example-single-request }

### Use HTTP(S) proxy that requires authentication

In order to use a HTTP(S) proxy that requires authentication, you need to provide @apidoc[HttpCredentials] that will be used
when making the CONNECT request to the proxy:


Scala
:  @@snip [HttpClientExampleSpec.scala]($test$/scala/docs/http/scaladsl/HttpClientExampleSpec.scala) { #auth-https-proxy-example-single-request }

Java
:  @@snip [HttpClientExampleDocTest.java]($test$/java/docs/http/javadsl/HttpClientExampleDocTest.java) { #auth-https-proxy-example-single-request }

### Use HTTP(S) proxy with @scala[Http().singleWebSocketRequest]@java[Http.get(...).singleWebSocketRequest]

Making use of an HTTP proxy when using the `singleWebSocketRequest` is done like using `singleRequest`, except you set `ClientConnectionSettings`
instead of `ConnectionPoolSettings`:

Scala
:  @@snip [WebSocketClientExampleSpec.scala]($test$/scala/docs/http/scaladsl/WebSocketClientExampleSpec.scala) { #https-proxy-singleWebSocket-request-example }

Java
:  @@snip [WebSocketClientExampleTest.java]($test$/java/docs/http/javadsl/WebSocketClientExampleTest.java) { #https-proxy-singleWebSocket-request-example }

### Use HTTP(S) proxy that requires authentication for Web Sockets

Here is an example for Web Socket:

Scala
:  @@snip [WebSocketClientExampleSpec.scala]($test$/scala/docs/http/scaladsl/WebSocketClientExampleSpec.scala) { #auth-https-proxy-singleWebSocket-request-example }

Java
:  @@snip [WebSocketClientExampleTest.java]($test$/java/docs/http/javadsl/WebSocketClientExampleTest.java) { #auth-https-proxy-singleWebSocket-request-example }


## Implementing Custom Transports

Implement `ClientTransport.connectTo` to implement a custom client transport.

Here are some ideas for custom (or future predefined) transports:

 * SSH tunnel transport: connects to the target host through an SSH tunnel
 * Per-host configurable transport: allows choosing transports per target host

