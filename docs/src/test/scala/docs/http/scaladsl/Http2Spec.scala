/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.scaladsl

import akka.http.impl.util.ExampleHttpContexts
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse, StatusCodes }
import akka.stream.ActorMaterializer

//#bindAndHandleSecure
import scala.concurrent.Future

import akka.http.scaladsl.HttpsConnectionContext
//#bindAndHandleSecure

//#bindAndHandleSecure
//#bindAndHandlePlain
import akka.http.scaladsl.Http
//#bindAndHandlePlain

//#bindAndHandleSecure

//#bindAndHandlePlain
import akka.http.scaladsl.HttpConnectionContext

//#bindAndHandlePlain

import akka.actor.ActorSystem
import akka.stream.Materializer

object Http2Spec {
  val asyncHandler: HttpRequest => Future[HttpResponse] = _ => Future.successful(HttpResponse(status = StatusCodes.ImATeapot))
  val httpsServerContext: HttpsConnectionContext = ExampleHttpContexts.exampleServerContext
  implicit val system: ActorSystem = ActorSystem()
  implicit val materializer: Materializer = ActorMaterializer()

  //#bindAndHandleSecure
  Http().bindAndHandleAsync(
    asyncHandler,
    interface = "localhost",
    port = 8443,
    httpsServerContext)
  //#bindAndHandleSecure

  //#bindAndHandlePlain
  Http().bindAndHandleAsync(
    asyncHandler,
    interface = "localhost",
    port = 8080,
    connectionContext = HttpConnectionContext())
  //#bindAndHandlePlain
}
