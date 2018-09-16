# CVE-2018-16131: Denial of Service via unlimited decoding with decodeRequest directive ("zip bomb")

## Date

2018-09-05

## Description

Directives `decodeRequest` and `decodeRequestWith` which handle compressed request data did not limit the amount of uncompressed
data flowing out of it. In combination with common request directives like `entity(as)`, `toStrict`, or `formField`, this can lead
to excessive memory usage ultimately leading to an out of memory situation when highly compressed data is received
(so-called "Zip Bomb").

Any code that uses `decodeRequest` or `decodeRequestWith` is likely to be affected.

## Severity

The CVSS score of this vulnerability is 7.3 (High), based on vector
[AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:H/E:H/RL:W/RC:C](https://nvd.nist.gov/vuln-metrics/cvss/v3-calculator?vector=AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:H/E:H/RL:W/RC:C).

Rationale for the score:

 * A:H: Server runs into OOM, so availability is highly affected.
 * E:H: It's relatively simple to exploit.

## Affected Versions

All previously released Akka HTTP versions are affected:

 * `10.1.x` versions prior to `10.1.5`
 * `10.0.x` versions prior to `10.0.14`
 * Earlier end-of-lifed versions

Not affected:

 * Play and Lagom applications, even though both are using Akka HTTP as their server backend,
   remain unaffected by this vulnerability. This is because they implement their own content
   length validations on top of the underlying models (by using `BodyParser`s).

## Fixed Versions

 * 10.1.5
 * 10.0.14
