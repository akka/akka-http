# Unsupported HTTP method: PRI

```
Illegal request, responding with status '501 Not Implemented': Unsupported HTTP method: PRI
```

This indicates that a HTTP/2 request was received, but the server was not
correctly set up to handle those. You may have to:

* Add the @ref[`akka-http2-support` dependency](../server-side/http2.md#dependency) to the classpath
* Make sure the @ref[`akka.http.server.preview.enable-http2` option](../server-side/http2.md#enable-http-2-support) is enabled
* Make sure you are running @ref[at least JDK version 8u252](../server-side/http2.md#dependency)
* Make sure you are not using @apidoc[Http().bindAndHandle()](Http$) or @apidoc[Http().newServerAt().bindFlow()](ServerBuilder), but @apidoc[Http().newServerAt().bind()](ServerBuilder).
