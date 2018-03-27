/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.scaladsl

import akka.http.scaladsl.model.{ HttpRequest, HttpResponse }
import akka.http.scaladsl.{ Always, Http, HttpConnectionContext, HttpsConnectionContext }

//#bindAndHandleAsync
import scala.concurrent.Future

//#bindAndHandleAsync

//#bindAndHandleAsync
import akka.http.scaladsl.Http2

//#bindAndHandleAsync

import akka.actor.ActorSystem
import akka.stream.Materializer

object Http2Spec {
  val asyncHandler: HttpRequest ⇒ Future[HttpResponse] = ???
  val httpsServerContext: HttpsConnectionContext = ???
  implicit val system: ActorSystem = ???
  implicit val materializer: Materializer = ???

  //#bindAndHandleAsync
  Http().bindAndHandleAsync(
    asyncHandler,
    interface = "localhost",
    port = 8443,
    httpsServerContext)
  //#bindAndHandleAsync

  //#bindAndHandleRaw
  Http2().bindAndHandleAsync(
    asyncHandler,
    interface = "localhost",
    port = 8080,
    connectionContext = HttpConnectionContext(http2 = Always))
  //#bindAndHandleRaw
}
