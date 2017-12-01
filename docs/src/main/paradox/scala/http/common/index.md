# Common Abstractions (Client- and Server-Side)

HTTP and related specifications define a great number of concepts and functionality that is not specific to either
HTTP's client- or server-side since they are meaningful on both end of an HTTP connection.
The documentation for their counterparts in Akka HTTP lives in this section rather than in the ones for the
@ref[Client-Side API](../client-side/index.md), @ref[Low-Level Server-Side API](../server-side/low-level-api.md) or @ref[High-level Server-Side API](../routing-dsl/index.md),
which are specific to one side only.

@@toc { depth=3 }

@@@ index

* [http-model](http-model.md)
* [uri-model](uri-model.md)
* [marshalling](marshalling.md)
* [unmarshalling](unmarshalling.md)
* [encoding](encoding.md)
* [json-support](json-support.md)
* [xml-support](xml-support.md)
* [timeouts](timeouts.md)
* [caching](caching.md)

@@@
