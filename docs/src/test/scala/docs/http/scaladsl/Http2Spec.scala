/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.scaladsl

import akka.http.impl.util.ExampleHttpContexts
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse, StatusCodes }

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
  implicit val system: ActorSystem = ActorSystem()

  {
    val asyncHandler: HttpRequest => Future[HttpResponse] = _ => Future.successful(HttpResponse(status = StatusCodes.ImATeapot))
    val httpsServerContext: HttpsConnectionContext = ExampleHttpContexts.exampleServerContext

    //#bindAndHandleSecure
    Http().newServerAt(interface = "localhost", port = 8443).enableHttps(httpsServerContext).bind(asyncHandler)
    //#bindAndHandleSecure
  }

  {
    import akka.http.scaladsl.server.Route
    import akka.http.scaladsl.server.directives.RouteDirectives.complete

    val handler: HttpRequest => Future[HttpResponse] =
      Route.toFunction(complete(StatusCodes.ImATeapot))
    //#bindAndHandlePlain
    Http().newServerAt("localhost", 8080).bind(handler)
    //#bindAndHandlePlain
  }
}
