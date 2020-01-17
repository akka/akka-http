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

* To set Routing or Parser settings, set them in your actor system configuration, e.g. via `application.conf`
* To apply a custom @apidoc[http.*.RejectionHandler] or @apidoc[ExceptionHandler], use the @ref[handleRejections](../routing-dsl/directives/execution-directives/handleRejections.md) and @ref[handleExceptions](../routing-dsl/directives/execution-directives/handleExceptions.md) directives