# setCookie

@@@ div { .group-scala }

## Signature

@@signature [CookieDirectives.scala](/akka-http/src/main/scala/akka/http/scaladsl/server/directives/CookieDirectives.scala) { #setCookie }

@@@

## Description

Adds a header to the response to request the update of the cookie with the given name on the client.

Use the @ref[deleteCookie](deleteCookie.md) directive to delete a cookie.

## Example

Scala
:  @@snip [CookieDirectivesExamplesSpec.scala](/docs/src/test/scala/docs/http/scaladsl/server/directives/CookieDirectivesExamplesSpec.scala) { #setCookie }

Java
:  @@snip [CookieDirectivesExamplesTest.java](/docs/src/test/java/docs/http/javadsl/server/directives/CookieDirectivesExamplesTest.java) { #setCookie }
