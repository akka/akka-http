# optionalCookie

## Signature

@@signature [CookieDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/CookieDirectives.scala) { #optionalCookie }

## Description

Extracts an optional cookie with a given name from a request.

Use the @ref[cookie](cookie.md) directive instead if the inner route does not handle a missing cookie.

## Example

@@snip [CookieDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/CookieDirectivesExamplesSpec.scala) { #optionalCookie }