# Migration Guide within Akka HTTP 10.0.x

## General Notes
Akka HTTP is binary backwards compatible within for all version within the 10.0.x range. However, there are a set of APIs
that are marked with the special annotation `@ApiMayChange` where this binary backwards compatibility is not guaranteed.
See @extref:[The @DoNotInherit and @ApiMayChange markers](akka-docs:common/binary-compatibility-rules.html#The_@DoNotInherit_and_@ApiMayChange_markers) in the Akka documentation for further information.

## Akka HTTP 10.0.x -> 10.0.6

### `AkkaHttp#route` has been renamed to `AkkaHttp#routes`

In order to provide a more descriptive name, `AkkaHttp#route` has been renamed to `AkkaHttp#routes`. The previous name
might have led to some confusion by wrongly implying that only one route could be returned in that method.
To migrate to 10.0.6, you must rename the old `route` method to `routes`.