# Server-Side HTTP/2

## Enable HTTP/2 support

HTTP/2 can then be enabled through configuration:

```
akka.http.server.enable-http2 = on
```

## Use `newServerAt(...).bind()` and HTTPS

HTTP/2 is primarily used over a secure HTTPS connection which takes care of protocol negotiation and falling back to HTTP/1.1 over TLS when the client does not support HTTP/2.
See the @ref[HTTPS section](server-https-support.md) for how to set up HTTPS.

You can use @scala[@scaladoc[Http().newServerAt(...).bind()](akka.http.scaladsl.ServerBuilder)]@java[@javadoc[Http().get(system).newServerAt(...).bind()](akka.http.javadsl.ServerBuilder)] as long as you followed the above steps:

Scala
:   @@snip[Http2Spec.scala](/docs/src/test/scala/docs/http/scaladsl/Http2Spec.scala) { #bindAndHandleSecure }

Java
:   @@snip[Http2Test.java](/docs/src/test/java/docs/http/javadsl/Http2Test.java) { #bindAndHandleSecure }

Note that currently only `newServerAt(...).bind` and `newServerAt(...).bindSync`
support HTTP/2 but not `bindFlow` or `connectionSource(): Source`.

HTTP/2 over TLS needs [Application-Layer Protocol Negotiation (ALPN)](https://en.wikipedia.org/wiki/Application-Layer_Protocol_Negotiation)
to negotiate whether both client and server support HTTP/2. The JVM provides ALPN support starting from JDK 8u252.
Make sure to use at least that version.

### HTTP/2 without HTTPS

While un-encrypted connections are allowed by HTTP/2, this is [sometimes discouraged](https://http2.github.io/faq/#does-http2-require-encryption).

There are 2 ways to implement un-encrypted HTTP/2 connections: by using the
[HTTP Upgrade mechanism](https://httpwg.org/specs/rfc7540.html#discover-http)
or by starting communication in HTTP/2 directly which requires the client to
have [Prior Knowledge](https://httpwg.org/specs/rfc7540.html#known-http) of
HTTP/2 support.

We support both approaches transparently on the same port. This feature is automatically enabled when HTTP/2 is enabled:

Scala
:   @@snip[Http2Spec.scala](/docs/src/test/scala/docs/http/scaladsl/Http2Spec.scala) { #bindAndHandlePlain }

Java
:   @@snip[Http2Test.java](/docs/src/test/java/docs/http/javadsl/Http2Test.java) { #bindAndHandlePlain }

#### h2c Upgrade

The advantage of switching from HTTP/1.1 to HTTP/2 using the
[HTTP Upgrade mechanism](https://httpwg.org/specs/rfc7540.html#discover-http)
is that both HTTP/1.1 and HTTP/2 clients can connect to the server on the
same port, without being aware beforehand which protocol the server supports.

The disadvantage is that relatively few clients support switching to HTTP/2
in this way. Additionally, HTTP/2 communication cannot start until the first
request has been completely sent. This means if your first request may be
large, it might be worth it to start with an empty OPTIONS request to switch
to HTTP/2 before sending your first 'real' request, at the cost of a roundtrip.

#### h2c with prior knowledge

The other option is to connect and start communicating in HTTP/2 immediately.
The downside of this approach is the client must know beforehand that the
server supports HTTP/2.
For the reason this approach is known as h2c with
[Prior Knowledge](https://httpwg.org/specs/rfc7540.html#known-http) of HTTP/2
support.

## Trailing headers

Like in the [HTTP/1.1 'Chunked' transfer encoding](https://datatracker.ietf.org/doc/html/rfc7230#section-4.1.2),
HTTP/2 supports a [trailer part](https://httpwg.org/specs/rfc7540.html#rfc.section.8.1) containing headers
after the body. Akka HTTP currently doesn't expose the trailing headers of the request. For the response, you
can either model the trailing headers as the @scala[@scaladoc[HttpEntity.LastChunk](akka.http.scaladsl.model.HttpEntity.LastChunk)]@java[last chunk]
of a @scala[@scaladoc[HttpEntity.Chunked](akka.http.scaladsl.model.HttpEntity.Chunked)]@java[chunked] response entity, or use the
@apidoc[trailer](AttributeKeys$) attribute:

Scala
:   @@snip[Http2Spec.scala](/docs/src/test/scala/docs/http/scaladsl/Http2Spec.scala) { #trailingHeaders }

Java
:   @@snip[Http2Test.java](/docs/src/test/java/docs/http/javadsl/Http2Test.java) { #trailingHeaders }

Having both a `trailingHeaders` attribute and a `LastChunk` element is not supported.

## Testing with cURL

At this point you should be able to connect, but HTTP/2 may still not be available.

You'll need a recent version of [cURL](https://curl.haxx.se/) compiled with HTTP/2 support (for OSX see [this article](https://simonecarletti.com/blog/2016/01/http2-curl-macosx/)). You can check whether your version supports HTTP2 with `curl  --version`, look for the nghttp2 extension and the HTTP2 feature:

```
curl 7.52.1 (x86_64-pc-linux-gnu) libcurl/7.52.1 OpenSSL/1.0.2l zlib/1.2.8 libidn2/0.16 libpsl/0.17.0 (+libidn2/0.16) libssh2/1.8.0 nghttp2/1.23.1 librtmp/2.3
Protocols: dict file ftp ftps gopher http https imap imaps ldap ldaps pop3 pop3s rtmp rtsp scp sftp smb smbs smtp smtps telnet tftp
Features: AsynchDNS IDN IPv6 Largefile GSS-API Kerberos SPNEGO NTLM NTLM_WB SSL libz TLS-SRP HTTP2 UnixSockets HTTPS-proxy PSL
```

When you connect to your service you may now see something like:

```
$ curl -k -v https://localhost:8443
(...)
* ALPN, offering h2
* ALPN, offering http/1.1
(...)
* ALPN, server accepted to use h2
(...)
> GET / HTTP/1.1
(...)
< HTTP/2 200
(...)
```

If your curl output looks like above, you have successfully configured HTTP/2. However, on JDKs up to version 9, it is likely to look like this instead:

```
$ curl -k -v https://localhost:8443
(...)
* ALPN, offering h2
* ALPN, offering http/1.1
(...)
* ALPN, server did not agree to a protocol
(...)
> GET / HTTP/1.1
(...)
< HTTP/1.1 200 OK
(...)
```

This shows `curl` declaring it is ready to speak `h2` (the shorthand name of HTTP/2), but could not determine whether the server is ready to, so it fell back to HTTP/1.1. To make this negotiation work you'll have to configure ALPN as described below.
