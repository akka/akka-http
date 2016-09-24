# Encoding / Decoding

The [HTTP spec](http://tools.ietf.org/html/rfc7231#section-3.1.2.1) defines a `Content-Encoding` header, which signifies whether the entity body of an HTTP message is
"encoded" and, if so, by which algorithm. The only commonly used content encodings are compression algorithms.

Currently Akka HTTP supports the compression and decompression of HTTP requests and responses with the `gzip` or
`deflate` encodings.
The core logic for this lives in the @github[akka.http.scaladsl.coding](/akka-http/src/main/scala/akka/http/scaladsl/coding) package.

The support is not enabled automatically, but must be explicitly requested.
For enabling message encoding/decoding with @ref[Routing DSL](../routing-dsl/index.md#http-high-level-server-side-api) see the @ref[CodingDirectives](../routing-dsl/directives/coding-directives/index.md#codingdirectives).