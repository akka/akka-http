JSON marshalling
----------------

When exercising the app, you interacted with JSON payloads. How does the example app convert data between JSON format and data that can be used by Scala classes? The answer begins in the `JsonFormats` object:

@@snip [JsonFormats.scala](/samples/akka-http-quickstart-scala/src/main/scala/com/example/JsonFormats.scala) { #json-formats }

We're using the [Spray JSON](https://github.com/spray/spray-json) library here, which allows us to define json marshallers
(or `formats` which is what Spray JSON calls them) in a type-safe way. In other words, if we don't provide a format instance for 
a type, yet we'd try to return it in a route by calling `complete(someValue)` the code would not compile - saying that
it does not know how to marshal the `SomeValue` type. This has the up-side of us being completely in control over what 
we want to expose, and not exposing some type accidentally in our HTTP API.

To handle the two different payloads, the trait defines two implicit values; `userJsonFormat` and `usersJsonFormat`. Defining the formatters as `implicit` ensures that the compiler can map the formatting functionality with the case classes to convert.

The `jsonFormatX` methods come from Spray JSON. The `X` represents the number of parameters in the underlying case classes:

@@snip [UserRegistry.scala](/samples/akka-http-quickstart-scala/src/main/scala/com/example/UserRegistry.scala) { #user-case-classes }

We won't go into how the formatters are implemented - this is done for us by the library. All you need to remember for now is to define the formatters as implicit and that the formatter used should map the number of parameters belonging to the case class it converts.

With these formatters defined, we can import them into the scope of the routes definition so that they are available where
we want to use them:

@@snip [UserRoutes.scala](/samples/akka-http-quickstart-scala/src/main/scala/com/example/UserRoutes.scala) { #import-json-formats }

@@@ note
  
While we used Spray JSON in this example, various other libraries are supported via the [Akka HTTP JSON](https://github.com/hseeberger/akka-http-json) 
project, including [Jackson](https://github.com/FasterXML/jackson), [Play JSON](https://www.playframework.com/documentation/2.6.x/ScalaJson) 
or [circe](https://circe.github.io/circe/).

Each library comes with different trade-offs in performance and user-friendlieness. Spray JSON is generally the fastest, though it requires you to write the format values explicitly. If you'd rather make "everything" automatically marshallable into JSON values you might want to use Jackson or Circe instead. 

If you're not sure, we recommend sticking to Spray JSON as it's the closest in philosophy to Akka HTTP - being explicit about all capabilities.
  
@@@ 

Now that we've examined the example app thoroughly, let's test a few remaining use cases.

