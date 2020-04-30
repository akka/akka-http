/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.scaladsl

import docs.CompileOnlySpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class WebSocketClientExampleSpec extends AnyWordSpec with Matchers with CompileOnlySpec {

  "half-closed-WebSocket-closing-example" in compileOnlySpec {
    import akka.actor.ActorSystem
    import akka.NotUsed
    import akka.http.scaladsl.Http
    import akka.stream.scaladsl._
    import akka.http.scaladsl.model.ws._

    implicit val system = ActorSystem()

    //#half-closed-WebSocket-closing-example

    // we may expect to be able to to just tail
    // the server websocket output like this
    val flow: Flow[Message, Message, NotUsed] =
      Flow.fromSinkAndSource(
        Sink.foreach(println),
        Source.empty)

    Http().singleWebSocketRequest(
      WebSocketRequest("ws://example.com:8080/some/path"),
      flow)

    //#half-closed-WebSocket-closing-example
  }

  "half-closed-WebSocket-working-example" in compileOnlySpec {
    import akka.actor.ActorSystem
    import akka.http.scaladsl.Http
    import akka.stream.scaladsl._
    import akka.http.scaladsl.model.ws._

    import scala.concurrent.Promise

    implicit val system = ActorSystem()

    //#half-closed-WebSocket-working-example

    // using Source.maybe materializes into a promise
    // which will allow us to complete the source later
    val flow: Flow[Message, Message, Promise[Option[Message]]] =
      Flow.fromSinkAndSourceMat(
        Sink.foreach[Message](println),
        Source.maybe[Message])(Keep.right)

    val (upgradeResponse, promise) =
      Http().singleWebSocketRequest(
        WebSocketRequest("ws://example.com:8080/some/path"),
        flow)

    // at some later time we want to disconnect
    promise.success(None)
    //#half-closed-WebSocket-working-example
  }

  "half-closed-WebSocket-finite-working-example" in compileOnlySpec {
    import akka.actor.ActorSystem
    import akka.http.scaladsl.Http
    import akka.stream.scaladsl._
    import akka.http.scaladsl.model.ws._

    import scala.concurrent.Promise

    implicit val system = ActorSystem()

    //#half-closed-WebSocket-finite-working-example

    // using emit "one" and "two" and then keep the connection open
    val flow: Flow[Message, Message, Promise[Option[Message]]] =
      Flow.fromSinkAndSourceMat(
        Sink.foreach[Message](println),
        Source(List(TextMessage("one"), TextMessage("two")))
          .concatMat(Source.maybe[Message])(Keep.right))(Keep.right)

    val (upgradeResponse, promise) =
      Http().singleWebSocketRequest(
        WebSocketRequest("ws://example.com:8080/some/path"),
        flow)

    // at some later time we want to disconnect
    promise.success(None)
    //#half-closed-WebSocket-finite-working-example
  }

  "authorized-singleWebSocket-request-example" in compileOnlySpec {
    import akka.actor.ActorSystem
    import akka.NotUsed
    import akka.http.scaladsl.Http
    import akka.stream.scaladsl._
    import akka.http.scaladsl.model.headers.{ Authorization, BasicHttpCredentials }
    import akka.http.scaladsl.model.ws._

    implicit val system = ActorSystem()
    import collection.immutable.Seq

    val flow: Flow[Message, Message, NotUsed] =
      Flow.fromSinkAndSource(
        Sink.foreach(println),
        Source.empty)

    //#authorized-single-WebSocket-request
    val (upgradeResponse, _) =
      Http().singleWebSocketRequest(
        WebSocketRequest(
          "ws://example.com:8080/some/path",
          extraHeaders = Seq(Authorization(
            BasicHttpCredentials("johan", "correcthorsebatterystaple")))),
        flow)
    //#authorized-single-WebSocket-request
  }

  "https-proxy-singleWebSocket-request-example" in compileOnlySpec {
    //#https-proxy-singleWebSocket-request-example
    import java.net.InetSocketAddress

    import akka.actor.ActorSystem
    import akka.NotUsed
    import akka.http.scaladsl.{ ClientTransport, Http }
    import akka.http.scaladsl.settings.ClientConnectionSettings
    import akka.http.scaladsl.model.ws._
    import akka.stream.scaladsl._

    implicit val system = ActorSystem()

    val flow: Flow[Message, Message, NotUsed] =
      Flow.fromSinkAndSource(
        Sink.foreach(println),
        Source.single(TextMessage("hello world!")))

    val proxyHost = "localhost"
    val proxyPort = 8888

    val httpsProxyTransport = ClientTransport.httpsProxy(InetSocketAddress.createUnresolved(proxyHost, proxyPort))

    val settings = ClientConnectionSettings(system).withTransport(httpsProxyTransport)
    Http().singleWebSocketRequest(WebSocketRequest(uri = "wss://example.com:8080/some/path"), clientFlow = flow, settings = settings)
    //#https-proxy-singleWebSocket-request-example
  }

  "https-proxy-singleWebSocket-request-example with auth" in compileOnlySpec {
    import java.net.InetSocketAddress

    import akka.actor.ActorSystem
    import akka.NotUsed
    import akka.http.scaladsl.{ ClientTransport, Http }
    import akka.http.scaladsl.settings.ClientConnectionSettings
    import akka.http.scaladsl.model.ws._
    import akka.stream.scaladsl._

    implicit val system = ActorSystem()

    val flow: Flow[Message, Message, NotUsed] =
      Flow.fromSinkAndSource(
        Sink.foreach(println),
        Source.single(TextMessage("hello world!")))

    val proxyHost = "localhost"
    val proxyPort = 8888

    //#auth-https-proxy-singleWebSocket-request-example
    import akka.http.scaladsl.model.headers

    val proxyAddress = InetSocketAddress.createUnresolved(proxyHost, proxyPort)
    val auth = headers.BasicHttpCredentials("proxy-user", "secret-proxy-pass-dont-tell-anyone")

    val httpsProxyTransport = ClientTransport.httpsProxy(proxyAddress, auth)

    val settings = ClientConnectionSettings(system).withTransport(httpsProxyTransport)
    Http().singleWebSocketRequest(WebSocketRequest(uri = "wss://example.com:8080/some/path"), clientFlow = flow, settings = settings)
    //#auth-https-proxy-singleWebSocket-request-example
  }

}
