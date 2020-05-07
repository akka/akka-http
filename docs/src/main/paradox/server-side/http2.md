# Server-Side HTTP/2 (Preview)

@@@ warning

Server-Side HTTP/2 support in akka-http is currently available as a preview.
This means it is ready to be evaluated, but the APIs and behavior are likely to change.

@@@

## Dependency

To use Akka HTTP2 Support, add the module to your project:

@@dependency [sbt,Gradle,Maven] {
  group="com.typesafe.akka"
  artifact="akka-http2-support_$scala.binary.version$"
  version="$project.version$"
}

HTTP/2 needs support in TLS for [Application-Layer Protocol Negotiation (ALPN)](https://en.wikipedia.org/wiki/Application-Layer_Protocol_Negotiation)
to negotiate whether both client and server support HTTP/2. The JVM provides ALPN support starting from JDK 8u252.
Make sure you run a JVM greater than that.

## Enable HTTP/2 support

HTTP/2 can then be enabled through configuration:

```
akka.http.server.preview.enable-http2 = on
```

## Use `bindAndHandleAsync` and HTTPS

HTTP/2 is primarily used over a secure connection (known as "over HTTPS" or "with TLS"), which also takes care of protocol negotiation and falling back to plain HTTPS when the client does not support HTTP/2.
See the @ref[HTTPS section](server-https-support.md) for how to set up HTTPS.

You can use @scala[@scaladoc[Http().bindAndHandleAsync](akka.http.scaladsl.HttpExt)]@java[@javadoc[Http().get(system).bindAndHandleAsync()](akka.http.javadsl.HttpExt)] as long as you followed the above steps:

Scala
:   @@snip[Http2Spec.scala]($test$/scala/docs/http/scaladsl/Http2Spec.scala) { #bindAndHandleSecure }

Java
:   @@snip[Http2Test.java]($test$/java/docs/http/javadsl/Http2Test.java) { #bindAndHandleSecure }

Note that `bindAndHandle` currently does not support HTTP/2, you must use `bindAndHandleAsync`.

### HTTP/2 without HTTPS

While un-encrypted connections are allowed by HTTP/2, this is [sometimes discouraged](https://http2.github.io/faq/#does-http2-require-encryption).

There are 2 ways to implement un-encrypted HTTP/2 connections: by using the
[HTTP Upgrade mechanism](https://httpwg.org/specs/rfc7540.html#discover-http)
or by starting communication in HTTP/2 directly which requires the client to
have [Prior Knowledge](https://httpwg.org/specs/rfc7540.html#known-http) of
HTTP/2 support.

We support both approaches transparently on the same port. This feature is automatically enabled when HTTP/2 is enabled:

Scala
:   @@snip[Http2Spec.scala]($test$/scala/docs/http/scaladsl/Http2Spec.scala) { #bindAndHandlePlain }

Java
:   @@snip[Http2Test.java]($test$/java/docs/http/javadsl/Http2Test.java) { #bindAndHandlePlain }

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

## Testing with cURL

At this point you should be able to connect, but HTTP/2 may still not be available.

You'll need a recent version of [cURL](https://curl.haxx.se/) compiled with HTTP/2 support (for OSX see [this article](https://simonecarletti.com/blog/2016/01/http2-curl-macosx/)). You can check whether your version supports HTTP2 with `curl --version`, look for the nghttp2 extension and the HTTP2 feature:

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
