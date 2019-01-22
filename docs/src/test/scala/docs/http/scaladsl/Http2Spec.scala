/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.scaladsl

import akka.http.scaladsl.model.{ HttpRequest, HttpResponse }

//#bindAndHandleAsync
import scala.concurrent.Future

import akka.http.scaladsl.{ Http, HttpsConnectionContext }
//#bindAndHandleAsync

//#bindAndHandleAsync
//#bindAndHandleWithoutNegotiation
import akka.http.scaladsl.Http2
//#bindAndHandleWithoutNegotiation

//#bindAndHandleAsync

//#bindAndHandleWithoutNegotiation
//#bindAndHandleNegotiateUpgrade
import akka.http.scaladsl.HttpConnectionContext
//#bindAndHandleNegotiateUpgrade
import akka.http.scaladsl.UseHttp2.Always

//#bindAndHandleWithoutNegotiation

//#bindAndHandleNegotiateUpgrade
import akka.http.scaladsl.UseHttp2.Negotiated

//#bindAndHandleNegotiateUpgrade

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

  //#bindAndHandleWithoutNegotiation
  Http2().bindAndHandleAsync(
    asyncHandler,
    interface = "localhost",
    port = 8080,
    connectionContext = HttpConnectionContext(http2 = Always))
  //#bindAndHandleWithoutNegotiation

  //#bindAndHandleNegotiateUpgrade
  Http2().bindAndHandleAsync(
    asyncHandler,
    interface = "localhost",
    port = 8080,
    connectionContext = HttpConnectionContext(http2 = Negotiated))
  //#bindAndHandleNegotiateUpgrade
}
