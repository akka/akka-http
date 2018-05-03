/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.scaladsl

import akka.http.scaladsl.model.{ HttpRequest, HttpResponse }

//#bindAndHandleSecure
import scala.concurrent.Future

import akka.http.scaladsl.{ Http, HttpsConnectionContext }
//#bindAndHandleSecure

//#bindAndHandleSecure
//#bindAndHandlePlain
import akka.http.scaladsl.Http2
//#bindAndHandlePlain

//#bindAndHandleSecure

//#bindAndHandlePlain
import akka.http.scaladsl.HttpConnectionContext

//#bindAndHandlePlain

//#bindAndHandleNegotiateUpgrade
import akka.http.scaladsl.UseHttp2.Negotiated

//#bindAndHandleNegotiateUpgrade

import akka.actor.ActorSystem
import akka.stream.Materializer

object Http2Spec {
  val asyncHandler: HttpRequest => Future[HttpResponse] = ???
  val httpsServerContext: HttpsConnectionContext = ???
  implicit val system: ActorSystem = ???
  implicit val materializer: Materializer = ???

  //#bindAndHandleSecure
  Http().bindAndHandleAsync(
    asyncHandler,
    interface = "localhost",
    port = 8443,
    httpsServerContext)
  //#bindAndHandleSecure

  //#bindAndHandlePlain
  Http2().bindAndHandleAsync(
    asyncHandler,
    interface = "localhost",
    port = 8080,
    connectionContext = HttpConnectionContext())
  //#bindAndHandlePlain
}
