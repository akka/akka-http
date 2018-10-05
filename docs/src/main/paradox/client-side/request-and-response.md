# HttpRequest and HttpResponse

All 3 Akka HTTP Client API levels use the same basic model of @unidoc[HttpRequest] and @unidoc[HttpResponse].

## Creating requests

You can create simple `GET` requests:

Scala
:  @@snip[HttpClientExampleSpec.scala](/docs/src/test/scala/docs/http/scaladsl/HttpClientExampleSpec.scala){ #create-simple-request }

Java
:  @@snip[ClientSingleRequestExample.java](/docs/src/test/java/docs/http/javadsl/ClientSingleRequestExample.java){ #create-simple-request }

Or more complicated ones, like this `POST`:

Scala
:  @@snip[HttpClientExampleSpec.scala](/docs/src/test/scala/docs/http/scaladsl/HttpClientExampleSpec.scala){ #create-post-request }

Java
:  @@snip[ClientSingleRequestExample.java](/docs/src/test/java/docs/http/javadsl/ClientSingleRequestExample.java){ #create-post-request }

See the API documentation of @unidoc[HttpRequest] for more information on how to customize your requests.

## Processing responses

When you receive a response, you can use the @ref[Marshalling](../common/marshalling.md) API to convert the response entity into an object:

Scala
:  @@snip[HttpClientExampleSpec.scala](/docs/src/test/scala/docs/http/scaladsl/HttpClientExampleSpec.scala){ #unmarshal-response-body }

Java
:  @@snip[ClientSingleRequestExample.java](/docs/src/test/java/docs/http/javadsl/ClientSingleRequestExample.java){ #unmarshal-response-body }
