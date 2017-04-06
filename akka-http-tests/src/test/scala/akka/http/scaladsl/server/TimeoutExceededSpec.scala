/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server

import akka.actor.ActorSystem
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.settings.{ ClientConnectionSettings, ConnectionPoolSettings }
import akka.http.scaladsl.{ Http, TestUtils }
import akka.stream.ActorMaterializer
import akka.testkit.{ SocketUtil, TestKit }
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpec }

import scala.concurrent.Await
import scala.concurrent.duration._

class TimeoutExceededSpec extends WordSpec with Matchers with RequestBuilding with BeforeAndAfterAll
  with ScalaFutures {

  val testConf: Config = ConfigFactory.parseString(
    """
    akka.loggers = ["akka.testkit.TestEventListener"]
    akka.loglevel = DEBUG
    akka.stdout-loglevel = DEBUG
    """)

  implicit val system = ActorSystem(getClass.getSimpleName, testConf)
  implicit val materializer = ActorMaterializer()
  import system.dispatcher

  val route =
    post {
      Thread.sleep(2.seconds.toMillis)
      complete("")
    }

  val port = SocketUtil.temporaryServerAddress().getPort
  val binding = Http().bindAndHandle(route, "localhost", port).futureValue

  "timeout" should {

    "work" in {
      val testTimeout = 500.millis
      val poolSettings = ConnectionPoolSettings(system)
        .withConnectionSettings(
          ClientConnectionSettings(system)
            .withConnectingTimeout(testTimeout)
            .withIdleTimeout(testTimeout)
        )

      println(s"  xxx poolSettings = ${poolSettings}")
      println(s"  xxx poolSettings.idleTimeout = ${poolSettings.idleTimeout}")
      println(s"  xxx ClientConnectionSettings: poolSettings.connectionSettings.idleTimeout = ${poolSettings.connectionSettings.idleTimeout}")

      val url = s"http://localhost:$port/"
      val ex = intercept[Exception] {
        Await.result(Http().singleRequest(Post(url), settings = poolSettings), 10.seconds)
      }

      ex.getMessage should include("no bytes passed in the last 500 milliseconds")
    }

  }

  override def afterAll() =
    try binding.unbind().futureValue
    finally TestKit.shutdownActorSystem(system)

  private def entityOfSize(size: Int) = HttpEntity(ContentTypes.`text/plain(UTF-8)`, "0" * size)
}
