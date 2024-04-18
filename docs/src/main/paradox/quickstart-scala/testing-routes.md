Testing routes
--------------

If you remember when we started out with our `QuickstartApp`, we decided to put the routes themselves into a separate 
class. Back there we said that we're doing this to separate the infrastructure code (setting up the actor system and 
wiring up all the dependencies and actors), from the routes, which should only declare what they need to work with,
and can therefore be a bit more focused on their task at hand. This of course leads us to better testability.

This separation, other than being a good idea on its own, was all for this moment! For when we want to write tests
to cover all our routes, without having to bring up the entire application. 

## Unit testing routes

There are multiple ways one can test an HTTP application of course, however lets start at the simplest and also quickest 
way: unit testing. In this style of testing, we won't even need to spin up an actual server - all the tests will be 
executed on the routes directly - without the need of hitting actual network. This is due to Akka HTTP's pure design
and separation between the network layer (represented as a bi-directional `Flow` of byte strings to Http domain objects).

In other words, unit testing in Akka HTTP is simply "executing" the routes by passing in an `HttpResponse` to the route,
and later inspecting what `HttpResponse` (or `rejection` if the request could not be handled) it resulted in. All this 
in-memory, without having to start a real HTTP server - which gives us supreme speed and turn-over time when developing
an application using Akka.

First we'll need to extend a number of base traits:

@@snip [QuickstartServer.scala](/samples/akka-http-quickstart-scala/src/test/scala/com/example/UserRoutesSpec.scala) { #test-top }

Here we're using ScalaTest which provides the testing *style* `WordSpec` and the `Matchers` trait which provides
the `something should === (somethingElse)` syntax [and more](http://www.scalatest.org/user_guide/using_matchers). 
Next we inherit the Akka HTTP provided `ScalatestRouteTest` bridge trait that provides Route specific testing facilities, 
and binds into ScalaTest's lifecycle methods such that the `ActorSystem` is started and stopped automatically for us.


@@@ note

If you're using Specs2 instead, you can simply extend the `Specs2RouteTest` support trait instead.

@@@ 


Next we'll need to bring into the test class our routes that we want to test. We're doing this by creating an instance of the `UserRoutes` and importing the marshallers from `JsonFormats` into scope for the tests to use, as well as makes it possible to implement all abstract members of that trait in the test itself - all in in a fully type-safe way.

We'll want to use the Akka `ActorTestKit` however the `ScalaRouteTest` only knows of the "classic" `ActorSystem` so we need 
to provide a small adapter allowing us to combine both. 

Next we need to have a `ActorRef[UserRegistry.Command]` available that that our routes will interact with. We are using the actual actor since it does not have any dependencies, this makes the test more like an integration test than a unit test. For a more isolated test a `TestProbe` from the Akka Testkit could be used - it would allow us to verify the route indeed did send a message do the Actor or not and send specific responses back in each test case. 

@@snip [QuickstartServer.scala](/samples/akka-http-quickstart-scala/src/test/scala/com/example/UserRoutesSpec.scala) { #set-up }

We could create an actor that replies with a mocked response here instead if we wanted to, this is especially useful if
the route awaits an response from the actor before rendering the `HttpResponse` to the client. Read about the @extref[Akka TestKit](akka-docs:testing.html) and it's utilities like `TestProbe` if this is something you'd like to learn more about. 

Let's write our first test, in which we'll hit the `/users` endpoint with a `GET` request:

@@snip [QuickstartServer.scala](/samples/akka-http-quickstart-scala/src/test/scala/com/example/UserRoutesSpec.scala) { #actual-test }

We simply construct a raw `HttpRequest` object and pass it into the route using the `~>` testing operator provided by `ScalatestRouteTest`. Next we do the same and pipe the result of that route into a check block, so the full syntax is: 
`request ~> route ~> check { }`. This syntax allows us to not worry about the asynchronous nature of the request handling.
After all, the route is a function of `HttpRequest => Future[HttpResponse]` - here we don't need to explicitly write code
that's awaiting on the response, it's handled for us.

Inside the check block we can inspect @ref[all kinds of attributes](../routing-dsl/testkit.md#table-of-inspectors) of the received response, like `status`, `contentType` and 
of course the full response which we can easily convert to a string for testing using `responseAs[String]`. This infrastructure
is using the same marshalling infrastructure as our routes, so if the response was a `User` JSON, we could say `responseAs[User]` and write our assertions on the actual object.

In the next test we'd like test a `POST` endpoint, so we need to send an entity to the endpoint in order to create a new `User`. This time, instead of using the raw `HttpRequest` to build the request we'll use a small DSL provided by the Akka HTTP. The DSL allows you to write `Post("/hello)` instead of having to declare the full thing in the raw API (which would have been: `HttpRequest(method = HttpMethods.POST, uri = "/hello")`), and next we'll add the User JSON into the request body: 

@@snip [QuickstartServer.scala](/samples/akka-http-quickstart-scala/src/test/scala/com/example/UserRoutesSpec.scala) { #testing-post }

So in order to add the entity we've used the `Marshal(object).to[TargetType]` syntax, which uses the same marshalling
infrastructure that is used when we `complete(object)`. Since we extend the `UserRoutes` trait in this test, all the 
necessary implicits for the marshalling to work this way are also present in scope of the test. This is another reason
why it's so convenient to extend the Routes trait when testing it - everything the actual code was using, we also have at
our disposal when writing the test.

This concludes the basics of unit testing HTTP routes, to learn more please refer to the 
[Akka HTTP TestKit documentation]().

### Complete unit test code listing

For reference, here's the entire unit test code:

@@snip [QuickstartServer.scala](/samples/akka-http-quickstart-scala/src/test/scala/com/example/UserRoutesSpec.scala) { #user-routes-spec }


## A note Integration testing routes

While definitions of "what a pure unit-test is" are sometimes a subject of fierce debates in programming communities,
we refer to the above testing style as "route unit testing" since it's light weight and allows to test the routes in 
isolation, especially if their dependencies would be mocked our with test stubs, instead of hitting real APIs.

Sometimes however one wants to test the complete "full application", including starting a real HTTP server

@@@ warning
  
  Some network specific features like timeouts, behaviour of entities (streamed directly from the network, instead of 
  in memory objects like in the unit testing style) may behave differently in the unit-testing style showcased above.
  
  If you want to test specific timing and entity draining behaviours of your apps you may want to add full integration tests for them. For most routes this should not be needed, however we'd recommend doing so when using more of the streaming features of Akka HTTP.
  
@@@

Usually such tests would be implemented by starting the application the same way as we started it in the `QuickstartServer`,
in `beforeAll` (in ScalaTest), then hitting the API with http requests using the HTTP Client and asserting on the responses,
finally shutting down the server in `afterAll` (in ScalaTest).

