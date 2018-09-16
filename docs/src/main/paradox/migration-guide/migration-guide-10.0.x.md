# Migration Guide within Akka HTTP 10.0.x

## General Notes
Akka HTTP is binary backwards compatible within for all version within the 10.0.x range. However, there are a set of APIs
that are marked with the special annotation `@ApiMayChange` which are exempt from this rule, in order to allow them to be
evolved more freely until stabilising them, by removing this annotation.
See @extref:[The @DoNotInherit and @ApiMayChange markers](akka-docs:common/binary-compatibility-rules.html#the-donotinherit-and-apimaychange-markers) for further information.

This migration guide aims to help developers who use these bleeding-edge APIs to migrate between their evolving versions
within patch releases.

## 10.0.13 -> 10.0.14

To avoid excessive memory usage we introduced two new limits that apply per default:

 * `akka.http.routing.decode-max-size`: This limit applies when you use `decodeRequest` to limit the amount of decompressed data. The default limit is 8 megabytes.
 * `akka.http.parsing.max-to-strict-bytes`: This limit applies when you use `HttpEntity.toStrict` or the `toStrictEntity` directive (and related directives). It will only collect up to the given amount data and fail otherwise. The default limit is 8 megabytes.

Depending on your application requirements, you may want to change these settings.

## 10.0.7 -> 10.0.8

### ClientTransport SPI / API Changes

@unidoc[ClientTransport] SPI and API have changed in @github[#1195](akka/akka-http#1195). `ClientTransport.TCP` is now constant
and doesn't take any parameters any more. `ClientTransport.connectTo` now has a new `settings: ClientConnectionSettings` parameter.
This is a binary and source incompatible change to an `@ApiMayChange` API. So far, this API was not documented or
exposed so we hope that only few users are affected.

## 10.0.6 -> 10.0.7

### `HttpApp#route` has been renamed to `HttpApp#routes`

In order to provide a more descriptive name, `HttpApp#route` has been renamed to `HttpApp#routes`. The previous name
might have led to some confusion by wrongly implying that only one route could be returned in that method.
To migrate to 10.0.6, you must rename the old `route` method to `routes`.
