/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.scaladsl

//#bindAndHandleAsync
import akka.http.scaladsl.Http

//#bindAndHandleAsync
import akka.actor.ActorSystem
import akka.stream.Materializer

object Http2Spec {
  val asyncHandler = ???
  val httpsServerContext = ???
  implicit val system: ActorSystem = ???
  implicit val materializer: Materializer = ???

  //#bindAndHandleAsync
  Http().bindAndHandleAsync(asyncHandler, interface = "localhost", port = 8443, httpsServerContext)
  //#bindAndHandleAsync
}
