# Migration Guide to and within Akka HTTP 10.2.x

## General Notes

See the general @ref[compatibility guidelines](../compatibility-guidelines.md).

## Akka HTTP 10.1.11 - > 10.2.0

### Providing route settings, exception and rejection handling

Previously, the implicit conversion `route2HandlerFlow` that turns a `Route` into a
`Flow[HttpRequest, HttpResponse]` allowed implicitly passing in Routing settings,
Parser settings, custom `Materializer`, `RoutingLog` or `ExecutionContext`, and
the `RejectionHandler`/`ExceptionHandler` to use.

This has been simplified to use system defaults instead:

* To set Routing or Parser settings, set them in your actor system configuration, e.g. via `application.conf`. In the rare case where you'd like to test routes with different @apidoc[RoutingSettings] within the same test, you can use the @ref[withSettings](../routing-dsl/directives/basic-directives/withSettings.md) directive.
* To apply a custom @apidoc[http.*.RejectionHandler] or @apidoc[ExceptionHandler], use the @ref[handleRejections](../routing-dsl/directives/execution-directives/handleRejections.md) and @ref[handleExceptions](../routing-dsl/directives/execution-directives/handleExceptions.md) directives

#### In RouteTest

Similarly, @apidoc[RouteTest] no longer automatically picks up such implicit values, and you
can use the same approach to explicitly set them instead.

### Strict query strings

In 10.1.x, while parsing the query string of a URI, characters were accepted that are
not allowed according to RFC 3986, even when `parsing.uri-parsing-mode` was
set to the default value of `strict`. Parsing such URIs will now fail in `strict` mode.
If you want to allow such characters in incoming URIs, set `parsing.uri-parsing-mode` to `relaxed`, in which case these characters will be percent-encoded automatically.
