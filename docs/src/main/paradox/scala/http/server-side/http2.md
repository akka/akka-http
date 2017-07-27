# Server-Side HTTP/2 (Preview)

Server-Side HTTP/2 support in akka-http is currently available as a preview.
This means it is ready to be evaluated, but the API's and behavior is likely to change.

## Enable HTTP/2 support

`akka-http2-support` must be added as a dependency:

sbt
:   @@@vars
    ```sbt
    "com.typesafe.akka" %% "akka-http2-support" % "$project.version$"
    ```
    @@@

maven
:   @@@vars
    ```
    <dependency>
        <groupId>com.typesafe.akka</groupId>
        <artifactId>akka-http2-support_2.12</artifactId>
        <version>$project.version$</version>
    </dependency>
    ```
    @@@

HTTP/2 can then be enabled through configuration:

```
akka.http.server.preview.enable-http2 = on
```

### Use `bindAndHandleAsync` and HTTPS

We only support HTTP/2 over HTTPS: browsers don't typically support HTTP/2 on plain HTTP, and other clients that do are rare.

You can use @scala[@scaladoc[Http().bindAndHandleAsync](akka.http.scaladsl.HttpExt)]@java[@javadoc[new Http().bindAndHandleAsync()](akka.http.javadsl.HttpExt)] as long as you followed the above steps:

Scala
:   @@snip[Http2Spec.scala](../../../../../test/scala/docs/http/scaladsl/Http2Spec.scala) { #bindAndHandleAsync }

Java
:   @@snip[Http2Test.java](../../../../../test/java/docs/http/javadsl/Http2Test.java) { #bindAndHandleAsync }

Note that `bindAndHandle` currently does not support HTTP/2, you must use `bindAndHandleAsync`.

### Testing with cURL

At this point you should be able to connect. You'll need a recent version of [cURL](https://curl.haxx.se/) compiled with HTTP/2 support (for OSX see [this article](https://simonecarletti.com/blog/2016/01/http2-curl-macosx/)). You can check whether your version supports HTTP2 with `curl --version`:

```
curl 7.52.1 (x86_64-pc-linux-gnu) libcurl/7.52.1 OpenSSL/1.0.2l zlib/1.2.8 libidn2/0.16 libpsl/0.17.0 (+libidn2/0.16) libssh2/1.8.0 nghttp2/1.23.1 librtmp/2.3
Protocols: dict file ftp ftps gopher http https imap imaps ldap ldaps pop3 pop3s rtmp rtsp scp sftp smb smbs smtp smtps telnet tftp
Features: AsynchDNS IDN IPv6 Largefile GSS-API Kerberos SPNEGO NTLM NTLM_WB SSL libz TLS-SRP HTTP2 UnixSockets HTTPS-proxy PSL
```

When you connect to your service you should now see:

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
```

This shows `curl` declaring it is ready to speak either `h2` (the shorthand name of HTTP/2) or HTTP/1.1, but because it could not determine whether the server is ready to it fell back to HTTP/1.1.

### Application-Layer Protocol Negotiation (ALPN)

[Application-Layer Protocol Negotiation (ALPN)](https://en.wikipedia.org/wiki/Application-Layer_Protocol_Negotiation) is used to negotiate whether both client and server support HTTP/2.

ALPN support comes with the JVM starting from version 9. If you're on a previous version of the JVM, you'll have to load a Java Agent to provide this functionality.

### sbt

sbt can be configured to load the agent with the [sbt-javaagent plugin](https://github.com/sbt/sbt-javaagent):

```
  .enablePlugins(JavaAgent)
  .settings(
    javaAgents += "org.mortbay.jetty.alpn" % "jetty-alpn-agent" % "2.0.6" % "runtime"
  )
```

This should automatically load the agent when running, testing, or even in distributions made with [sbt-native-package](https://github.com/sbt/sbt-native-packager).

### maven

// TODO