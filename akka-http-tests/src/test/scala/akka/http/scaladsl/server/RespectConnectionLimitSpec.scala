/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server

import akka.actor._
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.stream._
import akka.stream.scaladsl._
import akka.testkit.TestKit
import io.gatling.app.Gatling
import io.gatling.core.scenario.{ Simulation ⇒ GatlingSim }
import org.scalatest._
import java.util.concurrent.atomic._
import scala.concurrent._
import scala.concurrent.duration._
import scala.util._
import com.typesafe.config.ConfigFactory

class RespectConnectionLimitSpec extends AsyncWordSpec with Matchers with BeforeAndAfterAll {
  import RespectConnectionLimitSpec._

  implicit val system = ActorSystem("RespectConnectionLimitSpec", ConfigFactory.parseString(s"""
    akka.http.server.max-connections = $MaxConnections
  """))
  implicit val materializer = ActorMaterializer()

  "Http.bindAndHandle" should {

    "respect the max-connections setting when handling connections with empty upstream" in {

      val route = get {
        extractActorSystem { sys: ActorSystem ⇒
          val p = Promise[String]
          import sys.dispatcher
          sys.scheduler.scheduleOnce(1.second)(p.complete(Success("done")))
          Directives.complete(p.future)
        }
      }

      val connectionCount = new AtomicInteger()
      val tooManyConnections = new AtomicBoolean(false)

      val handler = Flow[HttpRequest]
        .via(Route.seal(route))
        .watchTermination() { (_, terminated) ⇒
          if (connectionCount.incrementAndGet > MaxConnections) {
            tooManyConnections.set(true)
          }
          terminated.onComplete { _ ⇒
            connectionCount.decrementAndGet()
          }
        }

      Http().bindAndHandle(handler, "localhost", 0).map { binding ⇒
        Simulation.port = binding.localAddress.getPort
        Gatling.fromArgs(Array("--mute", "--no-reports"), Some(classOf[Simulation.TooManyUsers].asInstanceOf[Class[GatlingSim]]))
        tooManyConnections.get shouldBe false
      }
    }

  }

  override def afterAll = TestKit.shutdownActorSystem(system)
}

object RespectConnectionLimitSpec {
  final val MaxConnections = 2
}

object Simulation {

  import io.gatling.core.Predef._
  import io.gatling.http.Predef._
  import io.gatling.http.request.builder.HttpRequestBuilder

  @volatile var port = 0

  class TooManyUsers() extends GatlingSim {

    val url = s"http://localhost:$port/"
    val getStatus: String ⇒ HttpRequestBuilder = aggPath ⇒ http("Status").get(aggPath).check(status.is(200))
    val statusScenario = scenario("MaxStatus").forever(exec(getStatus(url)))

    setUp(
      statusScenario
        .inject(atOnceUsers(6))
        .throttle(
          reachRps(6) in (1 seconds),
          holdFor(5 seconds)
        )
    ).disablePauses
  }

}
