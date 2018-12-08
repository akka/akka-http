/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.scaladsl.server.directives

import scala.concurrent.duration._

import akka.util.ByteString

import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{ Sink, Source, Flow }

import docs.http.scaladsl.server.RoutingSpec
import akka.http.scaladsl.model.ws.{ TextMessage, Message, StrictMessage, BinaryMessage }
import akka.http.scaladsl.testkit.WSProbe

class WebSocketDirectivesExamplesSpec extends RoutingSpec {
  "greeter-service" in {
    //#greeter-service
    def greeter: Flow[Message, Message, Any] =
      Flow[Message].mapConcat {
        case tm: TextMessage =>
          TextMessage(Source.single("Hello ") ++ tm.textStream ++ Source.single("!")) :: Nil
        case bm: BinaryMessage =>
          // ignore binary messages but drain content to avoid the stream being clogged
          bm.dataStream.runWith(Sink.ignore)
          Nil
      }
    val websocketRoute =
      path("greeter") {
        handleWebSocketMessages(greeter)
      }

    // tests:
    // create a testing probe representing the client-side
    val wsClient = WSProbe()

    // WS creates a WebSocket request for testing
    WS("/greeter", wsClient.flow) ~> websocketRoute ~>
      check {
        // check response for WS Upgrade headers
        isWebSocketUpgrade shouldEqual true

        // manually run a WS conversation
        wsClient.sendMessage("Peter")
        wsClient.expectMessage("Hello Peter!")

        wsClient.sendMessage(BinaryMessage(ByteString("abcdef")))
        wsClient.expectNoMessage(100.millis)

        wsClient.sendMessage("John")
        wsClient.expectMessage("Hello John!")

        wsClient.sendCompletion()
        wsClient.expectCompletion()
      }
    //#greeter-service
  }

  "handle-multiple-protocols" in {
    //#handle-multiple-protocols
    def greeterService: Flow[Message, Message, Any] =
      Flow[Message].mapConcat {
        case tm: TextMessage =>
          TextMessage(Source.single("Hello ") ++ tm.textStream ++ Source.single("!")) :: Nil
        case bm: BinaryMessage =>
          // ignore binary messages but drain content to avoid the stream being clogged
          bm.dataStream.runWith(Sink.ignore)
          Nil
      }

    def echoService: Flow[Message, Message, Any] =
      Flow[Message]
        // needed because a noop flow hasn't any buffer that would start processing in tests
        .buffer(1, OverflowStrategy.backpressure)

    def websocketMultipleProtocolRoute =
      path("services") {
        handleWebSocketMessagesForProtocol(greeterService, "greeter") ~
          handleWebSocketMessagesForProtocol(echoService, "echo")
      }

    // tests:
    val wsClient = WSProbe()

    // WS creates a WebSocket request for testing
    WS("/services", wsClient.flow, List("other", "echo")) ~>
      websocketMultipleProtocolRoute ~>
      check {
        expectWebSocketUpgradeWithProtocol { protocol =>
          protocol shouldEqual "echo"

          wsClient.sendMessage("Peter")
          wsClient.expectMessage("Peter")

          wsClient.sendMessage(BinaryMessage(ByteString("abcdef")))
          wsClient.expectMessage(ByteString("abcdef"))

          wsClient.sendMessage("John")
          wsClient.expectMessage("John")

          wsClient.sendCompletion()
          wsClient.expectCompletion()
        }
      }
    //#handle-multiple-protocols
  }

  "handle-messages-builder" in {
    //#handle-messages-builder
    def greeter: Flow[StrictMessage, Message, Any] =
      Flow[StrictMessage].map {
        case TextMessage.Strict(text)   => TextMessage("Hello " + text + "!")
        case BinaryMessage.Strict(data) => BinaryMessage(Source.single(ByteString("Binary message received: ") ++ data))
      }
    val timeout: FiniteDuration = 1.second
    val maxBytes: Long = 1000
    val websocketRoute =
      path("greeter") {
        handleWsMessages { builder =>
          builder.toStrictTimeout(timeout).maxStrictSize(maxBytes).handleWith(greeter)
        }
      }

    // tests:
    // create a testing probe representing the client-side
    val wsClient = WSProbe()

    // WS creates a WebSocket request for testing
    WS("/greeter", wsClient.flow) ~> websocketRoute ~>
      check {
        // check response for WS Upgrade headers
        isWebSocketUpgrade shouldEqual true

        wsClient.sendMessage(TextMessage(Source.single("Peter"))) // non-strict message
        wsClient.expectMessage("Hello Peter!")

        wsClient.sendMessage(BinaryMessage(Source.single(ByteString("abcdef")))) // non-strict message
        wsClient.expectMessage(ByteString("Binary message received: abcdef"))

        wsClient.sendMessage("John")
        wsClient.expectMessage("Hello John!")

        wsClient.sendCompletion()
        wsClient.expectCompletion()
      }
    //#handle-messages-builder
  }

  "handle-only-text-messages-builder" in {
    //#handle-only-text-messages-builder
    def greeter: Flow[StrictMessage, Message, Any] =
      Flow[StrictMessage].map {
        case TextMessage.Strict(text) => TextMessage("Hello " + text + "!")
      }
    val timeout: FiniteDuration = 1.second
    val websocketRoute =
      path("greeter") {
        handleWsMessages { builder =>
          builder.only[TextMessage].toStrictTimeout(timeout).handleWith(greeter)
        }
      }

    // tests:
    // create a testing probe representing the client-side
    val wsClient = WSProbe()

    // WS creates a WebSocket request for testing
    WS("/greeter", wsClient.flow) ~> websocketRoute ~>
      check {
        // check response for WS Upgrade headers
        isWebSocketUpgrade shouldEqual true

        wsClient.sendMessage(TextMessage(Source.single("Peter"))) // non-strict message
        wsClient.expectMessage("Hello Peter!")

        wsClient.sendMessage("John")
        wsClient.expectMessage("Hello John!")

        wsClient.sendCompletion()
        wsClient.expectCompletion()
      }
    //#handle-only-text-messages-builder
  }

  "handle-only-binary-messages-builder" in {
    //#handle-only-binary-messages-builder
    def greeter: Flow[StrictMessage, Message, Any] =
      Flow[StrictMessage].map {
        case BinaryMessage.Strict(data) => BinaryMessage(ByteString("Binary message received: ") ++ data)
      }
    val timeout: FiniteDuration = 1.second
    val websocketRoute =
      path("greeter") {
        handleWsMessages { builder =>
          builder.only[BinaryMessage].toStrictTimeout(timeout).handleWith(greeter)
        }
      }

    // tests:
    // create a testing probe representing the client-side
    val wsClient = WSProbe()

    // WS creates a WebSocket request for testing
    WS("/greeter", wsClient.flow) ~> websocketRoute ~>
      check {
        // check response for WS Upgrade headers
        isWebSocketUpgrade shouldEqual true

        wsClient.sendMessage(BinaryMessage(Source.single(ByteString("abcdef")))) // non-strict message
        wsClient.expectMessage(ByteString("Binary message received: abcdef"))

        wsClient.sendMessage(ByteString("abcdef"))
        wsClient.expectMessage(ByteString("Binary message received: abcdef"))

        wsClient.sendCompletion()
        wsClient.expectCompletion()
      }
    //#handle-only-binary-messages-builder
  }

  "extractUpgradeToWebSocket" in {
    //#extractUpgradeToWebSocket
    def echoService: Flow[Message, Message, Any] =
      Flow[Message]
        // needed because a noop flow hasn't any buffer that would start processing in tests
        .buffer(1, OverflowStrategy.backpressure)

    def route =
      path("services") {
        extractUpgradeToWebSocket { upgrade ⇒
          complete(upgrade.handleMessages(echoService, Some("echo")))
        }
      }

    // tests:
    val wsClient = WSProbe()

    // WS creates a WebSocket request for testing
    WS("/services", wsClient.flow, List("echo")) ~> route ~> check {
      expectWebSocketUpgradeWithProtocol { protocol =>
        protocol shouldEqual "echo"
        wsClient.sendMessage("ping")
        wsClient.expectMessage("ping")
        wsClient.sendCompletion()
        wsClient.expectCompletion()
      }
    }
    //#extractUpgradeToWebSocket
  }

  "extractOfferedWsProtocols" in {
    //#extractOfferedWsProtocols
    def echoService: Flow[Message, Message, Any] =
      Flow[Message]
        // needed because a noop flow hasn't any buffer that would start processing in tests
        .buffer(1, OverflowStrategy.backpressure)

    def route =
      path("services") {
        extractOfferedWsProtocols { protocols =>
          handleWebSocketMessagesForOptionalProtocol(echoService, protocols.headOption)
        }
      }

    // tests:
    val wsClient = WSProbe()

    // WS creates a WebSocket request for testing
    WS("/services", wsClient.flow, List("echo", "alfa", "kilo")) ~> route ~> check {
      expectWebSocketUpgradeWithProtocol { protocol =>
        protocol shouldEqual "echo"
        wsClient.sendMessage("ping")
        wsClient.expectMessage("ping")
        wsClient.sendCompletion()
        wsClient.expectCompletion()
      }
    }
    //#extractOfferedWsProtocols
  }
}
