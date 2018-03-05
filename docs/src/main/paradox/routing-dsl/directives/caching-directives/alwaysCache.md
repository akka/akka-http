# alwaysCache

@@@ div { .group-scala }

## Signature

@@signature [CachingDirectives.scala]($akka-http$/akka-http-caching/src/main/scala/akka/http/scaladsl/server/directives/CachingDirectives.scala) { #alwaysCache }

@@@

## Description

Like @ref[cache](cache.md) but disregards a `Cache-Control` request header.

## Example

Scala
:  @@snip [CachingDirectivesExamplesSpec.scala]($root$/src/test/scala/docs/http/scaladsl/server/directives/CachingDirectivesExamplesSpec.scala) { #always-cache }

Java
:  @@snip [CachingDirectivesExamplesTest.java]($root$/src/test/java/docs/http/javadsl/server/directives/CachingDirectivesExamplesTest.java) { #always-cache }
