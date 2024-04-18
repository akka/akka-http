HTTP Server Logic
-----------------

The main class, `QuickstartServer`, is runnable because it has a `main` method, as shown in the following snippet. 
This class is intended to "bring it all together", it is the main class that will start the `ActorSystem` with the root
behavior which bootstraps all actors and other dependencies (database connections etc).

@@snip [QuickstartServer.java](/samples/akka-http-quickstart-java/src/main/java/com/example/QuickstartApp.java) { #main-class }

Notice that we've separated out the `UserRoutes` class, in which we'll put all our actual route definitions.
This is a good pattern to follow, especially once your application starts to grow and you'll need some form of 
compartmentalizing them into groups of routes handling specific parts of the exposed API.

## Binding endpoints

Each Akka HTTP `Route` contains one or more `akka.http.javadsl.server.Directive`, such as: `path`, `get`, `post`, `complete`, etc. There is also a @ref[low-level API](../server-side/low-level-api.md) that allows to inspect requests and create responses manually. For the user registry service, the example needs to support the actions listed below. For each, we can identify a path, the HTTP method, and return value:

| Functionality      | HTTP Method | Path       | Returns              |
|--------------------|-------------|------------|----------------------|
| Create a user      | POST        | /users     | Confirmation message |
| Retrieve a user    | GET         | /users/$ID | JSON payload         |
| Remove a user      | DELETE      | /users/$ID | Confirmation message |
| Retrieve all users | GET         | /users     | JSON payload         |

In our app the definition of the `Route` is separated out into the class `UserRoutes` and available through the factory method `userRoutes`.

In larger applications we'd define separate subsystems in different places and then combine combine the various routes of our 
application into a big using the concat directive like this: `Route route = concat(UserRoutes.userRoutes(), healthCheckRoutes, ...)`

Let's look at the pieces of the example `Route` that bind the endpoints, HTTP methods, and message or payload for each action.

### Retrieving and creating users

The definition of the endpoint to retrieve and create users look like the following:

@@snip [UserRoutes.java](/samples/akka-http-quickstart-java/src/main/java/com/example/UserRoutes.java) { #users-get-post }

A Route is constructed by nesting various *directives* which route an incoming request to the apropriate handler block.
Note the following building blocks from the snippet:

**Generic functionality**

The following directives are used in the above example:

* `pathPrefix("users")` : the path that is used to match the incoming request against.
* `pathEnd` : used on an inner-level to discriminate “path already fully matched” from other alternatives. Will, in this case, match on the "users" path.
* `concat`: concatenates two or more route alternatives. Routes are attempted one after another. If a route rejects a request, the next route in the chain is attempted. This continues until a route in the chain produces a response. If all route alternatives reject the request, the concatenated route rejects the route as well. In that case, route alternatives on the next higher level are attempted. If the root level route rejects the request as well, then an error response is returned that contains information about why the request was rejected.

**Retrieving users**

* `get` : matches against `GET` HTTP method.
* `complete` : completes a request which means creating and returning a response from the arguments.

**Creating a user**

* `post` : matches against `POST` HTTP method.
* `entity(Unmarshaller<T>, T -> Directive))` : converts the HTTP request body into a domain object of type User. Implicitly, we assume that the request contains application/json content. We will look at how this works in the @ref:[JSON](json.md) section.
* `complete` : completes a request which means creating and returning a response from the arguments which can be different combinations of a HTTP status code, an object to "marshall" into a response body using a marshaller parameter.

### Retrieving and removing a user

Next, the example defines how to retrieve and remove a user. In this case, the URI must include the user's id in the form: `/users/$ID`. See if you can identify the code that handles that in the following snippet. This part of the route includes logic for both the GET and the DELETE methods.

@@snip [QuickstartServer.java](/samples/akka-http-quickstart-java/src/main/java/com/example/UserRoutes.java) { #users-get-delete }

This part of the `Route` contains the following:

**Generic functionality**

The following directives are used in the above example:

* `pathPrefix("users")` : the path that is used to match the incoming request against.
* `concat`: concatenates two or more route alternatives. Routes are attempted one after another. If a route rejects a request, the next route in the chain is attempted. This continues until a route in the chain produces a response. 
* `path(Segment) { user =>` : this bit of code matches against URIs of the exact format `/users/$ID` and the `Segment` is automatically extracted into the `user` variable so that we can get to the value passed in the URI. For example `/users/Bruce` will populate the `user` variable with the value "Bruce." There is plenty of more features available for handling of URIs, see @ref[pattern matchers](../routing-dsl/path-matchers.md#basic-pathmatchers) for more information.

**Retrieving a user**

* `get` : matches against `GET` HTTP method.
* `complete` : completes a request which means creating and returning a response from the arguments.

Let's break down the logic handling the incoming request:

@@snip [UserRoutes.java](/samples/akka-http-quickstart-java/src/main/java/com/example/UserRoutes.java) { #retrieve-user-info }

The `rejectEmptyResponse` here above is a convenience method that automatically unwraps a future, handles an `Option` by converting `Some` into a successful response, returns a HTTP status code 404 for `None`, and passes on to the `ExceptionHandler` in case of an error, which returns the HTTP status code 500 by default.

**Deleting a user**

* `delete` : matches against the Http directive `DELETE`.

The logic for handling delete requests is as follows:

@@snip [UserRoutes.java](/samples/akka-http-quickstart-java/src/main/java/com/example/UserRoutes.java) { #users-delete-logic }

So we send an instruction about removing a user to the user registry actor, wait for the response and return an appropriate HTTP status code to the client.

## The complete Route

Below is the complete `Route` definition from the sample application:

@@snip [UserRoutes.java](/samples/akka-http-quickstart-java/src/main/java/com/example/UserRoutes.java) { #all-routes }

Note that one might want to separate those routes into smaller route values and `concat` them together into the `userRoutes`
value - allowing for separation of concerns and get smaller routing trees.

## Binding the HTTP server

Binding the `Route` to a HTTP server on a TCP port is done from the root behavior actor on startup through the separate method 
`startHttpServer`, we have introduced it to avoid accidentally accessing internal state of the bootstrap actor.

The `bindAndhandle` method that does the actual binding takes three parameters; `routes`, the hostname, and the port.
Note that binding happens asynchronously and therefore the `bindAndHandle` method returns a `Future` which completes with 
an object representing the binding or fails if binding the HTTP route failed, for example if the port is already taken.

To make sure our application stops if it cannot bind we terminate the actor system if there is a failure.

@@snip [QuickstartApp.java](/samples/akka-http-quickstart-java/src/main/java/com/example/QuickstartApp.java) { #start-http-server }

In `QuickstartApp.java`, you will also find the code that ties everything together by starting the various actors in a 
root behavior. By watching the user registry actor and not handling the `Terminated` message we make sure that if
 it stops or craches the root behavior crashes and stops the `ActorSystem` itself. 

@@snip [QuickstartApp.java](/samples/akka-http-quickstart-java/src/main/java/com/example/QuickstartApp.java) { #main-class }



Let's move on to the actor that handles registration.
