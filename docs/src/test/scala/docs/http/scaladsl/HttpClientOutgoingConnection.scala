/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.scaladsl

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.stream.scaladsl._

import scala.concurrent.Future
import scala.util.{ Failure, Success }

object HttpClientOutgoingConnection {
  def main(args: Array[String]): Unit = {
    implicit val system = ActorSystem()
    implicit val executionContext = system.dispatcher

    val connectionFlow: Flow[HttpRequest, HttpResponse, Future[Http.OutgoingConnection]] =
      Http().outgoingConnection("akka.io")

    def dispatchRequest(request: HttpRequest): Future[HttpResponse] =
      // This is actually a bad idea in general. Even if the `connectionFlow` was instantiated only once above,
      // a new connection is opened every single time, `runWith` is called. Materialization (the `runWith` call)
      // and opening up a new connection is slow.
      //
      // The `outgoingConnection` API is very low-level. Use it only if you already have a `Source[HttpRequest, _]`
      // (other than Source.single) available that you want to use to run requests on a single persistent HTTP
      // connection.
      //
      // Unfortunately, this case is so uncommon, that we couldn't come up with a good example.
      //
      // In almost all cases it is better to use the `Http().singleRequest()` API instead.
      Source.single(request)
        .via(connectionFlow)
        .runWith(Sink.head)

    val responseFuture: Future[HttpResponse] = dispatchRequest(HttpRequest(uri = "/"))

    responseFuture.andThen {
      case Success(_) => println("request succeeded")
      case Failure(_) => println("request failed")
    }.andThen {
      case _ => system.terminate()
    }
  }
}
