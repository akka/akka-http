Testing routes
--------------

If you remember when we started out with our `QuickstartApp`, we decided to put the routes themselves into a separate class. Back there we said that we're doing this to separate the infrastructure code (setting up the actor system and wiring up all the dependencies and actors), from the routes, which should only declare what they need to work with, and can, therefore, be a bit more focused on their task at hand. This, of course, leads us to better testability.

This separation, other than being a good idea on its own, was all for this moment! For when we want to write tests to cover all our routes, without having to bring up the entire application. 

## Unit testing routes

There are multiple ways one can test an HTTP application of course, but lets start at the simplest and also quickest way: unit testing. In this style of testing, we won't even need to spin up an actual server - all the tests will be executed on the routes directly - without the need of hitting actual network. This is due to Akka HTTP's pure design and separation between the network layer (represented as a bi-directional `Flow` of byte strings to Http domain objects).

In other words, unit testing in Akka HTTP is simply "executing" the routes by passing in an `HttpResponse` to the route, and later inspecting what `HttpResponse` (or `rejection` if the request could not be handled) it resulted in. All this in-memory, without having to start a real HTTP server - which gives us supreme speed and turn-over time when developing an application using Akka.

First we'll need to extend a number of base traits:

@@snip [QuickstartServer.java](/samples/akka-http-quickstart-java/src/test/java/com/example/UserRoutesTest.java) { #test-top }

Here we're using `JUnitRouteTest` which provides ability to test akka-http routes. 

Next, we'll need to bring into the test class our routes that we want to test. We're doing this by wrapping put rout 
into `TestRoute` by using `testRoute(server.createRoute())` to be able to provide request parameters to emulate HTTP call 
and then assert results. You can assert HTML body or header values as well as response code itself.

@@snip [QuickstartServer.java](/samples/akka-http-quickstart-java/src/test/java/com/example/UserRoutesTest.java) { #set-up }

We could create an actor that replies with a mocked response here instead if we wanted to, this is especially useful if
the route awaits an response from the actor before rendering the `HttpResponse` to the client. 
Let's write our first test, in which we'll hit the `/users` endpoint with a `GET` request:

@@snip [QuickstartServer.java](/samples/akka-http-quickstart-java/src/test/java/com/example/UserRoutesTest.java) { #actual-test }

We simply construct a raw `HttpRequest` object and pass it into the route using the `run`.
Next, we do the same and pipe the result of that route into a check block, so the full syntax is:
`appRoute.run(HttpRequest.GET("/users"))`.

Inside the check block we can inspect all kinds of attributes of the received response, like `status`, `contentType` etc.
Checking looks like following: `assertStatusCode(StatusCodes.OK).assertMediaType("application/json")`

In the next test we'd like test a `POST` endpoint, so we need to send an entity to the endpoint in order to create a new
`User`. We are using similar approach `HttpRequest.POST("/users")` to what we have for GET test.

@@snip [QuickstartServer.java](/samples/akka-http-quickstart-java/src/test/java/com/example/UserRoutesTest.java) { #testing-post }

### Complete unit unit test code listing

For reference, here's the entire unit test code:

@@snip [QuickstartServer.java](/samples/akka-http-quickstart-java/src/test/java/com/example/UserRoutesTest.java)


## A note Integration testing routes

While definitions of "what a pure unit-test is" are sometimes a subject of fierce debates in programming communities,
we refer to the above testing style as "route unit testing" since it's lightweight and allows to test the routes in
isolation, especially if their dependencies would be mocked our with test stubs, instead of hitting real APIs.

Sometimes, however, one wants to test the complete "full application", including starting a real HTTP server

@@@ warning
  
  Some network specific features like timeouts, behavior of entities (streamed directly from the network, instead of
  in memory objects like in the unit testing style) may behave differently in the unit-testing style showcased above.
  
  If you want to test specific timing and entity draining behaviors of your apps you may want to add full integration
  tests for them. For most routes this should not be needed, however, we'd recommend doing so when using more of
  the streaming features of Akka HTTP.
  
@@@

Usually, such tests would be implemented by starting the application the same way as we started it in the `QuickstartServer`,
in `@BeforeClass` method, then hitting the API with http requests using the HTTP Client and asserting on the responses,
finally shutting down the server in `@AfterClass` .

