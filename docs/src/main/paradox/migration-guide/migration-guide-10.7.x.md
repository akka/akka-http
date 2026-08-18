# Migration Guide to and within Akka HTTP 10.7.x

## General Notes

See the general @ref[compatibility guidelines](../compatibility-guidelines.md).

Under these guidelines, minor version updates are supposed to be binary compatible and drop-in replacements
for former versions under the condition that user code only uses public, stable, non-deprecated API.

If you find an unexpected incompatibility please let us know.

No configuration changes are needed for updating an application from Akka HTTP 10.6.x to 10.7.x.

## HTTP/2 header block processing limits

Incoming HTTP/2 header blocks are now bounded by three new settings, on both the server
(`akka.http.server.http2`) and the client (`akka.http.client.http2`) side:

| Setting                   | Default | Bounds                                                                        |
|---------------------------|---------|-------------------------------------------------------------------------------|
| `max-header-block-size`   | `128k`  | The compressed (HPACK encoded) HEADERS plus CONTINUATION payloads for a stream |
| `max-continuation-frames` | `128`   | The number of CONTINUATION frames a single header block may be split over      |
| `max-header-list-size`    | `256k`  | The decoded header list, i.e. the sum of all header name and value lengths     |

Previously none of these were bounded, so the memory a single connection could use for header data
was not limited. Exceeding any of the limits now closes the connection with the HTTP/2 error
`ENHANCE_YOUR_CALM`.

The value of `max-header-list-size` is advertised to the peer as `SETTINGS_MAX_HEADER_LIST_SIZE`,
so well behaved peers will not exceed it. The defaults are far above what regular traffic uses,
but applications that legitimately exchange very large headers may need to raise them.

## Akka repository

@@@note
The Akka dependencies are available from Akka’s secure library repository. To access them you need to use a secure, tokenized URL as specified at https://account.akka.io/token.
@@@

## Dependency updates

### Akka

Akka HTTP 10.7.x requires Akka version >= 2.10.0.

### Jackson

The Jackson dependency has been updated to 2.17.2 in Akka HTTP 10.7.0. That bump includes many fixes and changes to
Jackson, but it should not introduce any incompatibility in serialized format.

### Remove dependency to scala-java8-compat

The transitive dependency on scala-java8-compat has been removed.

### Support for slf4j 1.7.x and logback 1.2.x removed

This is the first release that only supports slf4j 2.0.x and logback 1.5.x.

