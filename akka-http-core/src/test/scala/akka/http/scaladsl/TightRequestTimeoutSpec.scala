/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl

import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.model._
import akka.stream.scaladsl._
import akka.stream.{ OverflowStrategy, ActorMaterializer }
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.BeforeAndAfterAll
import scala.concurrent.duration._
import akka.testkit._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class TightRequestTimeoutSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll with ScalaFutures {
  val testConf: Config = ConfigFactory.parseString("""
    akka.loggers = ["akka.testkit.TestEventListener"]
    akka.loglevel = ERROR
    akka.stdout-loglevel = ERROR
    windows-connection-abort-workaround-enabled = auto
    akka.log-dead-letters = OFF
    akka.http.server.request-timeout = 10ms""")

  implicit val system = ActorSystem(getClass.getSimpleName, testConf)
  implicit val materializer = ActorMaterializer()
  implicit val patience = PatienceConfig(3.seconds.dilated)

  override def afterAll() = TestKit.shutdownActorSystem(system)

  "Tight request timeout" should {

    "not cause double push error caused by the late response attempting to push" in {
      val slowHandler = Flow[HttpRequest].map(_ => HttpResponse()).delay(500.millis.dilated, OverflowStrategy.backpressure)
      val binding = Http().bindAndHandle(slowHandler, "localhost", 0).futureValue
      val (hostname, port) = (binding.localAddress.getHostString, binding.localAddress.getPort)

      val p = TestProbe()
      system.eventStream.subscribe(p.ref, classOf[Logging.Error])

      val response = Http().singleRequest(HttpRequest(uri = s"http://$hostname:$port/")).futureValue
      response.status should ===(StatusCodes.ServiceUnavailable) // the timeout response

      p.expectNoMessage(1.second) // here the double push might happen

      binding.unbind().futureValue
    }

  }
}
