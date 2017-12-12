# logRequest

## Description

Logs the request. The directive is available with the following parameters:

>
 * A marker to prefix each log message with.
 * A log level.
 * A function that creates a @unidoc[LogEntry] from the @unidoc[HttpRequest]

Use `logResult` for logging the response, or `logRequestResult` for logging both.

## Example

@@snip [DebuggingDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/DebuggingDirectivesExamplesTest.java) { #logRequest }