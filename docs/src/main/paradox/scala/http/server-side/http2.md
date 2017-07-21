# HTTP/2 (Preview)

HTTP/2 support in akka-http is currently available as a preview.
This means it is ready to be evaluated, but the API's and behavior is likely to change.

@@@ div { .group-scala }

## Enabling HTTP/2

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

We only support HTTP/2 over HTTPS: browsers don't typically support HTTP/2 on plain HTTP, and other clients that do are rare.

To explicitly bind a server that accepts both HTTP and HTTP/2, simply use @scaladoc[Http2().bindAndHandleAsync](akka.http.scaladsl.Http2Ext) in the same way you would @scaladoc[Http().bindAndHandleAsync](akka.http.scaladsl.HttpExt):

Scala
:   @@snip[Http2Spec.scala](../../../../../test/scala/docs/http/scaladsl/Http2Spec.scala) { #bindAndHandleAsync }

Java
:   @@snip[Http2Test.java](../../../../../test/java/docs/http/javadsl/Http2Test.java) { #bindAndHandleAsync }

For an even more transparent experience, you can use the regular @scaladoc[Http().bindAndHandleAsync](akka.http.scaladsl.HttpExt) and enable HTTP/2 through configuration, but make sure you include the dependency and use the `Async` variation of `bindAndHandle`:

```
akka.http.server.preview.enable-http2 = on
```

At this point you should be able to connect, which you can verify with `curl -v`:

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

## Application-Layer Protocol Negotiation (ALPN)

[Application-Layer Protocol Negotiation (ALPN)](https://en.wikipedia.org/wiki/Application-Layer_Protocol_Negotiation) is used to negotiate whether both client and server support HTTP/2.

ALPN support comes with the JVM starting from version 9. If you're on a previous version of the JVM, you'll have to load a Java Agent to provide this functionality.

### sbt

When starting your application from within your `sbt` session with `run`, you will have to load the agent in your `SBT_OPTS` when starting `sbt`:

```
$ export SBT_OPTS="$SBT_OPTS -javaagent:/path/to/jetty-alpn-agent-2.0.6.jar"
$ sbt
```

When running or testing in a forked JVM, or in [sbt-native-package](https://github.com/sbt/sbt-native-packager) dists, you can use the [sbt-javaagent plugin](https://github.com/sbt/sbt-javaagent).

@@@

@@@ div { .group-java }

At this time no Java API is available for this feature.

@@@