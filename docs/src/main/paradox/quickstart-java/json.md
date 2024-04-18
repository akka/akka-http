JSON marshalling
----------------

When exercising the app, you interacted with JSON payloads. How does the example app convert data between JSON format and data that can be used by Java classes? Fur this purposes Akka HTTP has `akka.http.javadsl.marshallers.jackson.Jackson`

We're using the [Jackson](https://github.com/FasterXML/jackson) library here, along with akka-http wrapper that provides marshallers  `Jackson.marshaller()`.

In most cases you'll just need to provide the Jackson marshaller to `complete` call like following `complete(StatusCodes.OK, performed, Jackson.marshaller()` to be able to product JSON response.

To create objects from JSON you'll need to create unmarshaller with class as attribute  `Jackson.unmarshaller(User.class)`
That will work out of the box where you are using mutable Java Bean style objects with getters and setters but
in this sample we have instead used immutable messages and domain model classes which has no other methods than a constructor, the fields are all final and public. To make Jacksson understand how to create such objects from JSON this we need to provide some extra metadata in the form of the annotations `@JsonCreator` and `@JsonProperty`:

@@snip [UserRegistryActor.java](/samples/akka-http-quickstart-java/src/main/java/com/example/UserRegistry.java) { #user-case-classes }

While we used Jackson JSON in this example, the API is pluggable and various other libraries can be used. Each library comes with different trade-offs in performance and user-friendlieness. Still Jackson is the default Java marshaller as that is what we expect Java developers to be most familiar with.

Now that we've examined the example app thoroughly, let's test a few remaining use cases.

