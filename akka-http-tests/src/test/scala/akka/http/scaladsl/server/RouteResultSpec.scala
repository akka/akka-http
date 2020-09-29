package akka.http.scaladsl.server

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse, StatusCodes }
import akka.http.scaladsl.server.Directives.complete
import akka.stream.{ Materializer, SystemMaterializer }
import akka.stream.scaladsl.Flow
import akka.testkit.TestKit
import com.github.ghik.silencer.silent
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

@silent("never used")
class RouteResultSpec extends AnyWordSpec with Matchers {
  "RouteResult" should {
    val route: Route = complete(StatusCodes.OK)
    "provide a conversion from Route to Flow when an ActorSystem is available" in {
      implicit val system = ActorSystem("RouteResultSpec1")

      val flow: Flow[HttpRequest, HttpResponse, Any] = route

      TestKit.shutdownActorSystem(system, verifySystemShutdown = true)
    }

    "provide a conversion from Route to Flow when a Materializer is implicitily available" in {
      val system = ActorSystem("RouteResultSpec1")
      implicit val materializer: Materializer = SystemMaterializer(system).materializer

      val flow: Flow[HttpRequest, HttpResponse, Any] = route

      TestKit.shutdownActorSystem(system, verifySystemShutdown = true)
    }

    "provide a conversion from Route to Flow when both a Materializer and a system are implicitly  available" in {
      implicit val system = ActorSystem("RouteResultSpec1")
      implicit val materializer: Materializer = SystemMaterializer(system).materializer

      val flow: Flow[HttpRequest, HttpResponse, Any] = route

      TestKit.shutdownActorSystem(system, verifySystemShutdown = true)
    }
  }

}
