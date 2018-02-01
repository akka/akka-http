/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.scaladsl.server

import akka.{ Done, NotUsed }
import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.impl.engine.HttpIdleTimeoutException
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.{ Message, TextMessage, WebSocketRequest }
import akka.stream.Attributes.LogLevels
import akka.stream.{ ActorMaterializer, Attributes }
import akka.stream.scaladsl.{ Flow, Sink, Source }
import akka.stream.testkit.{ TestPublisher, TestSubscriber }
import akka.testkit.{ AkkaSpec, SocketUtil, TestKit }
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.{ IntegrationPatience, ScalaFutures }
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpecLike }

import scala.concurrent.Promise

class WebSocketIntegrationSpec extends WordSpecLike with Matchers with BeforeAndAfterAll with ScalaFutures with IntegrationPatience {

  implicit val system = ActorSystem(
    AkkaSpec.getCallerName(getClass),
    ConfigFactory.parseString(
      """
        akka.loglevel = debug # to see the timeout log entry
        akka.http.server.idle-timeout = 3 seconds
      """)
  )
  implicit val mat = ActorMaterializer()
  import system.dispatcher

  "The WebSocket" should {

    "fail the user flow on timeout" in new Server {

      val upstreamCompletion = Promise[Done]()
      val downstreamCompletion = Promise[Done]()

      def completeWithTermination[T](p: Promise[Done]) =
        Flow[T].watchTermination() { (_, fDone) ⇒
          p.completeWith(fDone)
          fDone.onComplete(c ⇒ system.log.debug("Saw termination: {}", c))
          NotUsed
        }

      def route =
        get {
          handleWebSocketMessages(
            completeWithTermination[Message](upstreamCompletion)
              .log("test-ws-log")
              .via(completeWithTermination[Message](downstreamCompletion))
          )
        }

      val toServer = TestPublisher.probe[Message]()
      val fromServer = TestSubscriber.probe[Message]()

      Http().singleWebSocketRequest(
        WebSocketRequest(s"ws://$host:$port"),
        Flow.fromSinkAndSource(Sink.fromSubscriber(fromServer), Source.fromPublisher(toServer))
      )._1.futureValue

      fromServer.request(1)

      val msg = TextMessage("bon giorno")
      toServer.sendNext(msg)
      fromServer.expectNext().asTextMessage shouldEqual msg

      Thread.sleep(3000)

      upstreamCompletion.future.failed.futureValue shouldBe a[HttpIdleTimeoutException]
      downstreamCompletion.future.failed.futureValue shouldBe a[HttpIdleTimeoutException]

      binding.unbind()
    }

  }

  trait Server extends Directives {
    def route: Route

    val (host, port) = SocketUtil.temporaryServerHostnameAndPort()
    val binding = Http().bindAndHandle(route, host, port).futureValue
  }

  override protected def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }
}
