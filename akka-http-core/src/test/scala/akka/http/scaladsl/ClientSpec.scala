/*
 * Copyright (C) 2017-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl

import akka.actor.ActorSystem
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.HttpMethods._
import akka.stream.ActorMaterializer
import com.typesafe.config.{ Config, ConfigFactory }
import scala.concurrent.duration._
import scala.concurrent.Await
import org.scalatest.BeforeAndAfterAll
import akka.testkit._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ClientSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {
  val testConf: Config = ConfigFactory.parseString("""
    akka.loggers = ["akka.testkit.TestEventListener"]
    akka.loglevel = ERROR
    akka.stdout-loglevel = ERROR
    windows-connection-abort-workaround-enabled = auto
    akka.log-dead-letters = OFF
    akka.http.server.request-timeout = infinite""")
  implicit val system = ActorSystem(getClass.getSimpleName, testConf)
  implicit val materializer = ActorMaterializer()

  override def afterAll() = TestKit.shutdownActorSystem(system)

  "HTTP Client" should {

    "reuse connection pool" in {
      val (hostname, port) = SocketUtil2.temporaryServerHostnameAndPort()
      val bindingFuture = Http().newServerAt(hostname, port).bindSync(_ => HttpResponse())
      val binding = Await.result(bindingFuture, 3.seconds.dilated)

      val respFuture = Http().singleRequest(HttpRequest(POST, s"http://$hostname:$port/"))
      val resp = Await.result(respFuture, 3.seconds.dilated)
      resp.status shouldBe StatusCodes.OK

      Await.result(Http().poolSize, 1.second.dilated) shouldEqual 1

      Http().singleRequest(HttpRequest(POST, s"http://$hostname:$port/"))
      val resp2 = Await.result(respFuture, 3.seconds.dilated)
      resp2.status shouldBe StatusCodes.OK

      Await.result(Http().poolSize, 1.second.dilated) shouldEqual 1

      Await.ready(binding.unbind(), 1.second.dilated)
    }
  }
}
