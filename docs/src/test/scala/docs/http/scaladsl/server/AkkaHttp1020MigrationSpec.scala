/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.scaladsl.server

import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route
import akka.stream.{ ActorMaterializer, Materializer }
import com.github.ghik.silencer.silent

@silent("since 10.2.0")
@silent("method apply in object ActorMaterializer is deprecated")
class AkkaHttp1020MigrationSpec {
  import akka.http.scaladsl.server.Directives._

  {
    //#old-binding
    // only worked with classic actor system
    implicit val system = akka.actor.ActorSystem("TheSystem")
    implicit val mat: Materializer = ActorMaterializer()
    val route: Route =
      get {
        complete("Hello world")
      }
    Http().bindAndHandle(route, "localhost", 8080)
    //#old-binding
  }

  {
    //#new-binding
    // works with both classic and typed ActorSystem
    implicit val system = akka.actor.typed.ActorSystem(Behaviors.empty, "TheSystem")
    // or
    // implicit val system = akka.actor.ActorSystem("TheSystem")

    // materializer not needed any more

    val route: Route =
      get {
        complete("Hello world")
      }
    Http().newServerAt("localhost", 8080).bind(route)
    //#new-binding
  }
}
