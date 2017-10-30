/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.stream.scaladsl.Source
import akka.stream.{ ActorMaterializer, StreamTcpException }
import akka.testkit.{ SocketUtil, TestKit }
import akka.util.ByteString
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpec }

import scala.concurrent.Await
import scala.concurrent.duration._

class WithIdleTimeoutSpec extends WordSpec with Matchers with RequestBuilding with BeforeAndAfterAll {
  val idleTimeout: FiniteDuration = 50 millis

  val testConf: Config = ConfigFactory.parseString(s"""
    akka.loggers = ["akka.testkit.TestEventListener"]
    akka.loglevel = ERROR
    akka.stdout-loglevel = ERROR
    akka.io.tcp.windows-connection-abort-workaround-enabled = auto
    akka.http.server.idle-timeout = ${idleTimeout.toMillis}ms""")
  implicit val system = ActorSystem(getClass.getSimpleName, testConf)
  import system.dispatcher
  implicit val materializer = ActorMaterializer()

  "A server response that is idle for longer than the configured akka.http.server.idle-timeout" should {
    "fail if using the default timeout" in {
      // Given
      val route: Route =
        get {
          complete(lazyEntity(4 * idleTimeout, "Hello Akka"))
        }

      val (hostName, port) = SocketUtil.temporaryServerHostnameAndPort()

      // When
      val entityF = for {
        _ ← Http().bindAndHandle(route, hostName, port)
        request = Get(s"http://$hostName:$port/")
        response ← Http().singleRequest(request)
        _ = response.status shouldEqual StatusCodes.OK
        strictEntity ← response.entity.toStrict(5 seconds)
      } yield strictEntity

      // Then
      intercept[StreamTcpException] {
        Await.result(entityF, 10 seconds)
      }
    }

    "fail via an withIdleTimeout directive with a sufficiently long timeout" in {
      // Given
      val route: Route =
        get {
          withIdleTimeout(2 * idleTimeout) {
            complete(lazyEntity(3 * idleTimeout, "Hello Akka"))
          }
        }

      val (hostName, port) = SocketUtil.temporaryServerHostnameAndPort()

      // When
      val entityF = for {
        _ ← Http().bindAndHandle(route, hostName, port)
        request = Get(s"http://$hostName:$port/")
        response ← Http().singleRequest(request)
        _ = response.status shouldEqual StatusCodes.OK
        strictEntity ← response.entity.toStrict(5 seconds)
      } yield strictEntity

      // Then
      intercept[StreamTcpException] {
        Await.result(entityF, 10 seconds)
      }
    }

    "be streamed through via an withIdleTimeout directive with a sufficiently long timeout" in {
      // Given
      val route: Route =
        get {
          withIdleTimeout(4 * idleTimeout) {
            complete(lazyEntity(3 * idleTimeout, "Hello Akka"))
          }
        }

      val (hostName, port) = SocketUtil.temporaryServerHostnameAndPort()

      // When
      val entityF = for {
        _ ← Http().bindAndHandle(route, hostName, port)
        request = Get(s"http://$hostName:$port/")
        response ← Http().singleRequest(request)
        _ = response.status shouldEqual StatusCodes.OK
        strictEntity ← response.entity.toStrict(5 seconds)
      } yield strictEntity

      // Then
      val entity = Await.result(entityF, 10 seconds)
      entity.data.utf8String shouldEqual "Hello Akka"
    }

    "be streamed through via the withoutIdleTimeout directive" in {
      // Given
      val route: Route =
        get {
          withoutIdleTimeout {
            complete(lazyEntity(3 * idleTimeout, "Hello Akka"))
          }
        }

      val (hostName, port) = SocketUtil.temporaryServerHostnameAndPort()

      // When
      val entityF = for {
        _ ← Http().bindAndHandle(route, hostName, port)
        request = Get(s"http://$hostName:$port/")
        response ← Http().singleRequest(request)
        _ = response.status shouldEqual StatusCodes.OK
        strictEntity ← response.entity.toStrict(5 seconds)
      } yield strictEntity

      // Then
      val entity = Await.result(entityF, 10 seconds)
      entity.data.utf8String shouldEqual "Hello Akka"
    }
  }

  override def afterAll() = TestKit.shutdownActorSystem(system)

  private def lazyEntity(idleDuration: FiniteDuration, body: String): HttpEntity.Chunked = {
    val source: Source[ByteString, _] =
      Source.tick(idleDuration, idleDuration, ByteString(body)).take(1)

    HttpEntity(ContentTypes.`text/plain(UTF-8)`, source)
  }
}
