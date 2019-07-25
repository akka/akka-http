# mapSettings

@@@ div { .group-scala }

## Signature

@@signature [BasicDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala) { #mapSettings }

@@@

## Description

Transforms the @apidoc[RoutingSettings] with a @scala[`RoutingSettings => RoutingSettings` function]@java[`Function<RoutingSettings, RoutingSettings>`].

See also @ref[withSettings](withSettings.md) or @ref[extractSettings](extractSettings.md).

## Example

Scala
:  @@snip [BasicDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala) { #mapSettings-examples }

Java
:  @@snip [BasicDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/BasicDirectivesExamplesTest.java) { #mapSettings }
