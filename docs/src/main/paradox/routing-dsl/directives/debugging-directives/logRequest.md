# logRequest

@@@ div { .group-scala }

## Signature

```scala
def logRequest(marker: String): Directive0
def logRequest(marker: String, level: LogLevel): Directive0
def logRequest(show: HttpRequest => String): Directive0
def logRequest(show: HttpRequest => LogEntry): Directive0
def logRequest(magnet: LoggingMagnet[HttpRequest => Unit]): Directive0
```

The signature shown is simplified, the real signature uses magnets. <a id="^1" href="#1">[1]</a>

> <a id="1" href="#^1">[1]</a> See [The Magnet Pattern](https://spray.readthedocs.io/en/latest/blog/2012-12-13-the-magnet-pattern.html) for an explanation of magnet-based overloading.

@@@

## Description

@scala[Logs the request using the supplied `LoggingMagnet[HttpRequest => Unit]` using the @apidoc[LoggingAdapter] of the @apidoc[RequestContext]. The `LoggingMagnet` is a wrapped
function `HttpRequest => Unit` that can be implicitly created from the different constructors shown above. These
constructors build a `LoggingMagnet` from these components:]
@java[Logs the request. The directive is available with the following parameters:]

@@@ div { .group-scala }
 * A marker to prefix each log message with.
 * A log level.
 * A `show` function that calculates a string representation for a request.
 * A function that creates a @apidoc[LogEntry] which is a combination of the elements above.
@@@
@@@ div { .group-java }
 * A marker to prefix each log message with.
 * A log level.
 * A function that creates a @apidoc[LogEntry] which is a combination of the elements above.
@@@

@scala[It is also possible to use any other function `HttpRequest => Unit` for logging by wrapping it with `LoggingMagnet`.
See the examples for ways to use the `logRequest` directive.]

Use `logResult` for logging the response, or `logRequestResult` for logging both.

@scala[To change the logger, wrap this directive by @ref[withLog](../basic-directives/withLog.md).]

## Example

Scala
:  @@snip [DebuggingDirectivesExamplesSpec.scala](/docs/src/test/scala/docs/http/scaladsl/server/directives/DebuggingDirectivesExamplesSpec.scala) { #logRequest-0 }

Java
:  @@snip [DebuggingDirectivesExamplesTest.java](/docs/src/test/java/docs/http/javadsl/server/directives/DebuggingDirectivesExamplesTest.java) { #logRequest }
