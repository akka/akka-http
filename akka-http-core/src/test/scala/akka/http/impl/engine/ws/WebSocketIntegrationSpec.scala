/**
 * Copyright (C) 2015-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.impl.engine.ws

import scala.concurrent.{ Await, Promise }
import scala.concurrent.duration.DurationInt
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.Uri.apply
import akka.http.scaladsl.model.ws._
import akka.stream._
import akka.stream.scaladsl._
import akka.stream.testkit._
import akka.stream.scaladsl.GraphDSL.Implicits._
import org.scalatest.concurrent.Eventually
import java.net.InetSocketAddress

import akka.Done
import akka.http.scaladsl.settings.ClientConnectionSettings
import akka.stream.stage.{ GraphStageLogic, GraphStageWithMaterializedValue, InHandler, OutHandler }
import akka.util.ByteString
import akka.stream.testkit.scaladsl.TestSink
import akka.testkit._

import scala.util.{ Failure, Success }

class WebSocketIntegrationSpec extends AkkaSpec("akka.stream.materializer.debug.fuzzing-mode=off")
  with Eventually {

  implicit val materializer = ActorMaterializer()

  "A WebSocket server" must {

    "not reset the connection when no data are flowing" in Utils.assertAllStagesStopped {
      val source = TestPublisher.probe[Message]()
      val bindingFuture = Http().bindAndHandleSync({
        case HttpRequest(_, _, headers, _, _) ⇒
          val upgrade = headers.collectFirst { case u: UpgradeToWebSocket ⇒ u }.get
          upgrade.handleMessages(Flow.fromSinkAndSource(Sink.ignore, Source.fromPublisher(source)), None)
      }, interface = "localhost", port = 0)
      val binding = Await.result(bindingFuture, 3.seconds.dilated)
      val myPort = binding.localAddress.getPort

      val flow = Flow.fromSinkAndSourceMat(TestSink.probe[Message], Source.maybe[Message])(Keep.both)

      val (response, (sink, promise)) = Http().singleWebSocketRequest(
        WebSocketRequest("ws://127.0.0.1:" + myPort),
        flow)

      response.futureValue.response.status.isSuccess should ===(true)
      sink
        .request(10)
        .expectNoMsg(500.millis.dilated)

      source
        .sendNext(TextMessage("hello"))
        .sendComplete()
      sink
        .expectNext(TextMessage("hello"))
        .expectComplete()

      promise.success(None)
      binding.unbind()
    }

    "not reset the connection when no data are flowing and close connection when the connection is closed from the client" in Utils.assertAllStagesStopped {
      val source = TestPublisher.probe[Message]()
      val bindingFuture = Http().bindAndHandleSync({
        case HttpRequest(_, _, headers, _, _) ⇒
          val upgrade = headers.collectFirst { case u: UpgradeToWebSocket ⇒ u }.get
          upgrade.handleMessages(Flow.fromSinkAndSource(Sink.ignore, Source.fromPublisher(source)), None)
      }, interface = "localhost", port = 0)
      val binding = Await.result(bindingFuture, 3.seconds.dilated)
      val myPort = binding.localAddress.getPort

      val flow = Flow.fromSinkAndSourceMat(TestSink.probe[Message], Source.maybe[Message])(Keep.both)

      val (response, (sink, promise)) = Http().singleWebSocketRequest(
        WebSocketRequest("ws://localhost:" + myPort),
        flow)

      response.futureValue.response.status.isSuccess should ===(true)
      sink
        .request(10)
        .expectNoMsg(1500.millis)

      promise.success(None)
      Thread.sleep(1000)

      source
        .sendNext(TextMessage("hello"))

      sink
        .expectComplete()
        .expectNoMsg(1500.millis)

      binding.unbind()
    }

    "echo 100 elements and then shut down without error" in Utils.assertAllStagesStopped {

      val bindingFuture = Http().bindAndHandleSync({
        case HttpRequest(_, _, headers, _, _) ⇒
          val upgrade = headers.collectFirst { case u: UpgradeToWebSocket ⇒ u }.get
          upgrade.handleMessages(Flow.apply, None)
      }, interface = "localhost", port = 0)
      val binding = Await.result(bindingFuture, 3.seconds.dilated)
      val myPort = binding.localAddress.getPort

      val N = 100

      EventFilter.warning(pattern = "HTTP header .* is not allowed in responses", occurrences = 0) intercept {
        val (response, (count, (sourceQueue, killSwitch))) = Http().singleWebSocketRequest(
          WebSocketRequest("ws://127.0.0.1:" + myPort),
          Flow.fromSinkAndSourceMat(
            Sink.fold(0)((n, _: Message) ⇒ n + 1),
            Source.queue[Message](100, OverflowStrategy.dropHead).viaMat(KillSwitches.single)(Keep.both)
          )(Keep.both))

        (1 to N).foreach(_ ⇒ sourceQueue.offer(TextMessage("tick")))
        Thread.sleep(1000)
        killSwitch.shutdown()
        count.futureValue should ===(N)
      }

      binding.unbind()
    }

    "send back 100 elements and then terminate without error even when not ordinarily closed" in Utils.assertAllStagesStopped {
      val N = 100

      val handler = Flow.fromGraph(GraphDSL.create() { implicit b ⇒
        val merge = b.add(Merge[Int](2))

        // convert to int so we can connect to merge
        val mapMsgToInt = b.add(Flow[Message].map(_ ⇒ -1))
        val mapIntToMsg = b.add(Flow[Int].map(x ⇒ TextMessage.Strict(s"Sending: $x")))

        // source we want to use to send message to the connected websocket sink
        val rangeSource = b.add(Source(1 to N))

        mapMsgToInt ~> merge // this part of the merge will never provide msgs
        rangeSource ~> merge ~> mapIntToMsg

        FlowShape(mapMsgToInt.in, mapIntToMsg.out)
      })

      val bindingFuture = Http().bindAndHandleSync({
        case HttpRequest(_, _, headers, _, _) ⇒
          val upgrade = headers.collectFirst { case u: UpgradeToWebSocket ⇒ u }.get
          upgrade.handleMessages(handler, None)
      }, interface = "localhost", port = 0)
      val binding = Await.result(bindingFuture, 3.seconds.dilated)
      val myPort = binding.localAddress.getPort

      @volatile var messages = 0
      val (switch, completion) =
        Source.maybe
          .viaMat {
            Http().webSocketClientLayer(WebSocketRequest("ws://localhost:" + myPort))
              .atop(TLSPlacebo())
              // the resource leak of #19398 existed only for severed websocket connections
              .atopMat(KillSwitches.singleBidi[ByteString, ByteString])(Keep.right)
              .join(Tcp().outgoingConnection(new InetSocketAddress("localhost", myPort), halfClose = true))
          }(Keep.right)
          .toMat(Sink.foreach(_ ⇒ messages += 1))(Keep.both)
          .run()
      eventually(messages should ===(N))
      // breaker should have been fulfilled long ago
      switch.shutdown()
      completion.futureValue

      binding.unbind()
    }

  }

  "A websocket client" should {
    "fail the materialized future if the request fails" in {
      val flow = Http().webSocketClientFlow(
        WebSocketRequest("ws://127.0.0.1:65535/no/server/here"),
        settings = ClientConnectionSettings(system).withConnectingTimeout(250.millis.dilated))

      val future = Source.maybe[Message].viaMat(flow)(Keep.right).toMat(Sink.ignore)(Keep.left).run()
      import system.dispatcher
      whenReady(future.map(r ⇒ Success(r)).recover { case ex ⇒ Failure(ex) }) { resTry ⇒
        resTry.isFailure should ===(true)
        resTry.failed.get.getMessage should ===("Connection failed.")
      }
    }
  }

}
