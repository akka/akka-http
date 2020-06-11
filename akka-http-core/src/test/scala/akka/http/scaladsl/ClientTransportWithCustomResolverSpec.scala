/*
 * Copyright (C) 2017-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl

import java.net.InetSocketAddress
import java.util.concurrent.TimeoutException

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model._
import akka.http.scaladsl.settings.{ ClientConnectionSettings, ConnectionPoolSettings }
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{ Keep, Sink, Source }
import akka.testkit._
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.{ BeforeAndAfterAll, OptionValues }
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.{ Await, ExecutionContext, Future, Promise }
import scala.concurrent.duration._

class ClientTransportWithCustomResolverSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll with OptionValues {
  val testConf: Config = ConfigFactory.parseString("""
    akka.loggers = ["akka.testkit.TestEventListener"]
    akka.loglevel = ERROR
    akka.stdout-loglevel = ERROR
    windows-connection-abort-workaround-enabled = auto
    akka.log-dead-letters = OFF
    akka.http.server.request-timeout = infinite""")
  implicit val system = ActorSystem(getClass.getSimpleName, testConf)

  override def afterAll() = TestKit.shutdownActorSystem(system)

  "A custom resolver" should {

    "change to the desired destination" in {
      val hostnameToFind = "some-name-out-there"
      val portToFind = 21345
      val (hostnameToUse, portToUse) = SocketUtil.temporaryServerHostnameAndPort()
      val bindingFuture = Http().bindAndHandleSync(_ => HttpResponse(), hostnameToUse, portToUse)
      val binding = Await.result(bindingFuture, 3.seconds.dilated)

      val otherHostAndPortTransport: ClientTransport = new ClientTransportWithCustomResolver {
        override protected def inetSocketAddress(host: String, port: Int, settings: ClientConnectionSettings)(implicit ec: ExecutionContext): Future[InetSocketAddress] = {
          host shouldBe hostnameToFind
          port shouldBe portToFind
          Future.successful(new InetSocketAddress(hostnameToUse, portToUse))
        }
      }

      val customResolverPool =
        ConnectionPoolSettings(system)
          .withConnectionSettings(ClientConnectionSettings(system).withTransport(otherHostAndPortTransport))

      val respFuture = Http().singleRequest(HttpRequest(POST, s"http://$hostnameToFind:$portToFind/"), settings = customResolverPool)
      val resp = Await.result(respFuture, 3.seconds.dilated)
      resp.status shouldBe StatusCodes.OK

      Await.ready(binding.unbind(), 1.second.dilated)
    }

    "resolve not before a connection is needed" in {
      val hostnameToFind = "some-name-out-there"
      val portToFind = 21345
      val (hostnameToUse, portToUse) = SocketUtil.temporaryServerHostnameAndPort()
      val bindingFuture = Http().bindAndHandleSync(_ => HttpResponse(), hostnameToUse, portToUse)
      val binding = Await.result(bindingFuture, 3.seconds.dilated)

      val resolverCalled = Promise[Done]

      val otherHostAndPortTransport: ClientTransport = new ClientTransportWithCustomResolver {
        override protected def inetSocketAddress(host: String, port: Int, settings: ClientConnectionSettings)(implicit ec: ExecutionContext): Future[InetSocketAddress] = {
          host shouldBe hostnameToFind
          port shouldBe portToFind
          resolverCalled.success(Done)
          Future.successful(new InetSocketAddress(hostnameToUse, portToUse))
        }
      }

      val customResolverPool =
        ConnectionPoolSettings(system)
          .withConnectionSettings(ClientConnectionSettings(system).withTransport(otherHostAndPortTransport))

      val (sourceQueue, sinkQueue) =
        Source.queue[(HttpRequest, Unit)](1, OverflowStrategy.backpressure)
          .via(Http().superPool[Unit](settings = customResolverPool))
          .toMat(Sink.queue())(Keep.both)
          .run()

      // nothing happens for at least 3 seconds
      assertThrows[TimeoutException](Await.result(resolverCalled.future, 3.seconds.dilated))

      // resolving kicks in when a request comes along
      sourceQueue.offer(HttpRequest(POST, s"http://$hostnameToFind:$portToFind/") -> ())
      Await.result(resolverCalled.future, 3.seconds.dilated) shouldBe Done
      val resp = Await.result(sinkQueue.pull(), 3.seconds.dilated).value._1.get
      resp.status shouldBe StatusCodes.OK

      Await.ready(binding.unbind(), 1.second.dilated)
    }
  }
}
