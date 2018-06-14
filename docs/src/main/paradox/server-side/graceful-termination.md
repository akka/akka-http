# Graceful termination

## Graceful termination using `ServerTerminator`

Akka HTTP provides two APIs to "stop" the server, either of them are available via the `ServerBinding` obtained from
starting the server (by using any of the `bind...` methods). 

The first method, called `unbind()` causes the server to *stop accepting new connections*, however any existing and 
still being used connections will remain active. This is by design and is in-line with what the method is called after,
simply unbinding the port on which the http server has been listening. This allows applications to finish streaming any 
responses that might be still in flight and eventually terminate the entire system. This however can lead to potentially
indefinitely continuing to respond to requests on those connections that remain open. 

A better and more graceful solution to terminate an Akka HTTP server is to use  @java[`ServerBinding.terminate(java.time.Duration)`]@scala[`ServerBinding.terminate(FiniteDuration)`] call, which not only performs the unbinding, but also 
handles replying to new incoming requests with (configurable) "terminating" http responses, up until the deadline is hit,
and still alive connections are shut down forcefully. More precisely, termination works by following these steps:

Step 1, the server port is unbound and no new connections will be accepted (same as invoking `unbind()`).
Immediately the ServerBinding `whenTerminationSignalIssued` future is completed.
This can be used to signal parts of the application that the http server is shutting down and they should clean up as well.
Note also that for more advanced shut down scenarios you may want to use the Coordinated Shutdown capabilities of Akka.

Step 2, in flight requests will be handled. If a request is "in-flight" (being handled by user code), it is given `hardDeadline` time to complete.
 
- if user code emits a response within the timeout, then this response is sent to the client
- however if it is a streaming response, it is also mandated that it shall complete within the deadline, and if it does not
  the connection will be terminated regardless of status of the streaming response (this is because such response could be infinite,
  which could trap the server in a situation where it could not terminate if it were to wait for a response to "finish")
    - existing streaming responses must complete before the deadline as well.
      When the deadline is reached the connection will be terminated regardless of status of the streaming responses.
- if user code does not reply with a response within the deadline, we produce a special @java[`akka.http.javadsl.settings.ServerSettings.getTerminationDeadlineExceededResponse`]@scala[`akka.http.scaladsl.settings.ServerSettings.terminationDeadlineExceededResponse`]  HTTP response (e.g. `503 Service Unavailable`)

Step 3, incoming requests are continue to be served. The existing connections will remain alive for until the 
`hardDeadline` is exceeded, yet no new requests will be delivered to the user handler. All such drained responses will be replied to with an termination response (as explained in step 2).

Step 4, all remaining alive connections are forcefully terminated once the `hardDeadline` is exceeded.
The `whenTerminated` (exposed by `ServerBinding`) @java[CompletionStage]@scala[future] is completed as well, so the
graceful termination (of the `ActorSystem` or entire JVM itself can be safely performed, as by then it is known that no
connections remain alive to this server).

Note that the termination response is configurable in `ServerSettings`, and by default is an `503 Service Unavailable`,
with an empty response entity.

Starting a graceful termination is as simple as invoking the terminate() method on the server binding:

Scala
:   @@snip [HttpServerExampleSpec.scala]($test$/scala/docs/http/scaladsl/HttpServerExampleSpec.scala) { #graceful-termination }

Java
:   @@snip [HttpServerExampleDocTest.java]($test$/java/docs/http/javadsl/server/HttpServerExampleDocTest.java) { #graceful-termination }

## Akka Coordinated Shutdown

@@@ note
  
  NOT IMPLEMENTED YET.
  
  Coordinated shutdown support is not yet implemented in Akka HTTP; 
  The goal is for it to invoke the graceful termination process as described above automatically when shutdown is requested.
  See the issue [#1210](https://github.com/akka/akka-http/issues/1210) for more details.

@@@

Coordinated shutdown is Akka's managed way of shutting down multiple modules / sub-systems (persistence, cluster, http etc)
in a predictable and ordered fashion. For example, in a typical Akka application you will want to stop accepting new HTTP connections, and then shut down the cluster etc. 
