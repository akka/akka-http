/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.server

import akka.http.impl.engine.ws.ByteStringSinkProbe
import akka.http.scaladsl.settings.ServerSettings

import scala.concurrent.duration.FiniteDuration
import akka.actor.ActorSystem
import akka.event.NoLogging
import akka.util.ByteString
import akka.stream._
import akka.stream.scaladsl._
import akka.stream.testkit.{ TestPublisher, TestSubscriber }
import akka.http.impl.util._
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.headers.{ ProductVersion, Server }
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse }

abstract class HttpServerTestSetupBaseUserFlow {
  implicit def system: ActorSystem
  implicit def materializer: Materializer

  def userFlow: Flow[HttpRequest, HttpResponse, Any]

  def settings = ServerSettings(system)
    .withServerHeader(Some(Server(List(ProductVersion("akka-http", "test")))))

  // hook to modify server, for example add attributes
  def modifyServer(server: Http.ServerLayer): Http.ServerLayer = server

  val (netIn, netOut) = {
    val netIn = TestPublisher.probe[ByteString]()
    val netOut = ByteStringSinkProbe()

    val net = Flow.fromSinkAndSource(netOut.sink, Source.fromPublisher(netIn))
    val serverImpl = modifyServer(HttpServerBluePrint(settings, log = NoLogging, isSecureConnection = false))
    val buffering = BidiFlow.fromFlows(Flow[ByteString].buffer(1, OverflowStrategy.backpressure), Flow[ByteString])

    userFlow
      .join(serverImpl atop TLSPlacebo() atop buffering)
      .join(net)
      .run()

    netIn → netOut
  }

  def expectResponseWithWipedDate(expected: String): Unit = {
    val trimmed = expected.stripMarginWithNewline("\r\n")
    // XXXX = 4 bytes, ISO Date Time String = 29 bytes => need to request 25 bytes more than expected string
    val expectedSize = ByteString(trimmed, "utf8").length + 25
    val received = wipeDate(netOut.expectBytes(expectedSize).utf8String)
    assert(received == trimmed, s"Expected request '$trimmed' but got '$received'")
  }

  def wipeDate(string: String) =
    string.fastSplit('\n').map {
      case s if s.startsWith("Date:") ⇒ "Date: XXXX\r"
      case s                          ⇒ s
    }.mkString("\n")

  def expectSubscribe(): Unit = netOut.expectComplete()
  def expectSubscribeAndNetworkClose(): Unit = netOut.expectSubscriptionAndComplete()
  def expectNetworkClose(): Unit = netOut.expectComplete()

  def send(data: ByteString): Unit = netIn.sendNext(data)
  def send(string: String): Unit = send(ByteString(string.stripMarginWithNewline("\r\n"), "UTF8"))

  def closeNetworkInput(): Unit = netIn.sendComplete()

  def shutdownBlueprint(): Unit = {
    netIn.sendComplete()
    shutdownUserFlow()
    netOut.expectBytes(ByteString("HTT")) // ???
    netOut.expectComplete()
  }

  def shutdownUserFlow(): Unit = {}
}

/** Implements the userFlow in terms of request and response probes */
abstract class HttpServerTestSetupBase extends HttpServerTestSetupBaseUserFlow {
  lazy val requests = TestSubscriber.probe[HttpRequest]
  lazy val responses = TestPublisher.probe[HttpResponse]()

  override def userFlow: Flow[HttpRequest, HttpResponse, Any] =
    Flow.fromSinkAndSource(Sink.fromSubscriber(requests), Source.fromPublisher(responses))

  def expectRequest(): HttpRequest = requests.requestNext()
  def expectNoRequest(max: FiniteDuration): Unit = requests.expectNoMessage(max)

  override def shutdownUserFlow(): Unit = {
    requests.expectComplete()
    responses.sendComplete()
  }
}
