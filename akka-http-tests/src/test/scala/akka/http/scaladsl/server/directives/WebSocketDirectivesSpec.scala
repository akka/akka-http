/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.server.directives

import java.util.concurrent.TimeoutException

import akka.util.ByteString

import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{ Source, Sink, Flow }

import akka.http.scaladsl.testkit.WSProbe

import akka.http.scaladsl.model.headers.`Sec-WebSocket-Protocol`
import akka.http.scaladsl.model.{ HttpResponse, StatusCodes, WebSocketStreamException }
import akka.http.scaladsl.model.ws._
import akka.http.scaladsl.server.{ UnsupportedWebSocketSubprotocolRejection, ExpectedWebSocketRequestRejection, Route, RoutingSpec }

import scala.concurrent.duration._

class WebSocketDirectivesSpec extends RoutingSpec {
  "the handleWebSocketMessages directive" should {
    "handle websocket requests" in {
      val wsClient = WSProbe()

      WS("http://localhost/", wsClient.flow) ~> websocketRoute ~>
        check {
          isWebSocketUpgrade shouldEqual true
          wsClient.sendMessage("Peter")
          wsClient.expectMessage("Hello Peter!")

          wsClient.sendMessage(BinaryMessage(ByteString("abcdef")))
          // wsClient.expectNoMessage() // will be checked implicitly by next expectation

          wsClient.sendMessage("John")
          wsClient.expectMessage("Hello John!")

          wsClient.sendCompletion()
          wsClient.expectCompletion()
        }
    }
    "choose subprotocol from offered ones" in {
      val wsClient = WSProbe()

      WS("http://localhost/", wsClient.flow, List("other", "echo", "greeter")) ~> websocketMultipleProtocolRoute ~>
        check {
          expectWebSocketUpgradeWithProtocol { protocol ⇒
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
    }
    "reject websocket requests if no subprotocol matches" in {
      WS("http://localhost/", Flow[Message], List("other")) ~> websocketMultipleProtocolRoute ~> check {
        rejections.collect {
          case UnsupportedWebSocketSubprotocolRejection(p) ⇒ p
        }.toSet shouldEqual Set("greeter", "echo")
      }

      WS("http://localhost/", Flow[Message], List("other")) ~> Route.seal(websocketMultipleProtocolRoute) ~> check {
        status shouldEqual StatusCodes.BadRequest
        responseAs[String] shouldEqual "None of the websocket subprotocols offered in the request are supported. Supported are 'echo','greeter'."
        header[`Sec-WebSocket-Protocol`].get.protocols.toSet shouldEqual Set("greeter", "echo")
      }
    }
    "reject non-websocket requests" in {
      Get("http://localhost/") ~> websocketRoute ~> check {
        rejection shouldEqual ExpectedWebSocketRequestRejection
      }

      Get("http://localhost/") ~> Route.seal(websocketRoute) ~> check {
        status shouldEqual StatusCodes.BadRequest
        responseAs[String] shouldEqual "Expected WebSocket Upgrade request"
      }
    }
  }

  "the handleWsMessages directive" should {
    "handle websocket requests by converting them to StrictMessage" in {
      val wsClient = WSProbe()

      WS("http://localhost/", wsClient.flow) ~> strictWebsocketRoute ~>
        check {
          isWebSocketUpgrade shouldEqual true
          wsClient.sendMessage(TextMessage(Source.single("John")))
          wsClient.expectMessage("Hello John!")

          wsClient.sendMessage(BinaryMessage(Source.single(ByteString("abcdef"))))
          wsClient.expectMessage(ByteString("Binary message received: abcdef"))

          wsClient.sendMessage("John")
          wsClient.expectMessage("Hello John!")

          wsClient.sendCompletion()
          wsClient.expectCompletion()
        }
    }
    "fail flow with TimeoutException when a message cannot be consumed within the timeout" in {
      val wsClient = WSProbe()

      WS("http://localhost/", wsClient.flow) ~> strictWebsocketRoute(1.millisecond) ~>
        check {
          isWebSocketUpgrade shouldEqual true

          wsClient.sendMessage(TextMessage(Source.repeat("J")))
          val error = wsClient.inProbe.expectSubscriptionAndError()
          error.getClass should be(classOf[TimeoutException])
        }
    }
    "fail flow with WebSocketStreamException when a message size is larger than the maximum setting" in {
      val wsClient = WSProbe()

      WS("http://localhost/", wsClient.flow) ~> websocketBuilder(_.toStrictTimeout(1.minute).maxStrictSize(1L).handleWith(strictGreeter)) ~>
        check {
          isWebSocketUpgrade shouldEqual true

          wsClient.sendMessage(TextMessage(Source.repeat("John")))
          val error = wsClient.inProbe.expectSubscriptionAndError()
          error.getClass should be(classOf[WebSocketStreamException])
        }
    }
    "choose subprotocol from offered ones" in {
      val wsClient = WSProbe()

      WS("http://localhost/", wsClient.flow, List("other", "echo", "greeter")) ~> strictWebsocketMultipleProtocolRoute ~>
        check {
          expectWebSocketUpgradeWithProtocol { protocol ⇒
            protocol shouldEqual "echo"

            wsClient.sendMessage(TextMessage(Source.single("Peter")))
            wsClient.expectMessage("Peter")

            wsClient.sendMessage(BinaryMessage(Source.single(ByteString("abcdef"))))
            wsClient.expectMessage(ByteString("abcdef"))

            wsClient.sendMessage("John")
            wsClient.expectMessage("John")

            wsClient.sendCompletion()
            wsClient.expectCompletion()
          }
        }
    }
    "reject websocket requests if no subprotocol matches" in {
      WS("http://localhost/", Flow[Message], List("other")) ~> Route.seal(websocketMultipleProtocolRoute) ~> check {
        status shouldEqual StatusCodes.BadRequest
        responseAs[String] shouldEqual "None of the websocket subprotocols offered in the request are supported. Supported are 'echo','greeter'."
        header[`Sec-WebSocket-Protocol`].get.protocols.toSet shouldEqual Set("greeter", "echo")
      }
    }
    "reject non-websocket requests" in {
      Get("http://localhost/") ~> strictWebsocketRoute ~> check {
        rejection shouldEqual ExpectedWebSocketRequestRejection
      }

      Get("http://localhost/") ~> Route.seal(strictWebsocketRoute) ~> check {
        status shouldEqual StatusCodes.BadRequest
        responseAs[String] shouldEqual "Expected WebSocket Upgrade request"
      }
    }
    "handle only text messages by converting them to strict form" in {
      val wsClient = WSProbe()

      WS("http://localhost/", wsClient.flow) ~> strictTextWebsocketRoute ~>
        check {
          isWebSocketUpgrade shouldEqual true
          wsClient.sendMessage(TextMessage(Source.single("John")))
          wsClient.expectMessage("Hello John!")

          wsClient.sendMessage("Peter")
          wsClient.expectMessage("Hello Peter!")

          wsClient.sendCompletion()
          wsClient.expectCompletion()
        }
    }
    "fail flow with RuntimeException when received a binary message while configured for text messages" in {
      val wsClient = WSProbe()

      WS("http://localhost/", wsClient.flow) ~> strictTextWebsocketRoute ~>
        check {
          wsClient.sendMessage(BinaryMessage(ByteString("abcdef")))
          val error = wsClient.inProbe.expectSubscriptionAndError()
          error.getClass should be(classOf[RuntimeException])
          error.getMessage should be("Received BinaryMessage while expecting TextMessage.")
        }
    }
    "fail flow with TimeoutException when a text message cannot be consumed within the timeout" in {
      val wsClient = WSProbe()

      WS("http://localhost/", wsClient.flow) ~> strictTextWebsocketRoute(1.millisecond) ~>
        check {
          isWebSocketUpgrade shouldEqual true

          wsClient.sendMessage(TextMessage(Source.repeat("John")))
          val error = wsClient.inProbe.expectSubscriptionAndError()
          error.getClass should be(classOf[TimeoutException])
        }
    }
    "handle only binary messages by converting them to strict form" in {
      val wsClient = WSProbe()

      WS("http://localhost/", wsClient.flow) ~> strictBinaryWebsocketRoute ~>
        check {
          isWebSocketUpgrade shouldEqual true
          wsClient.sendMessage(BinaryMessage(Source.single(ByteString("abcdef"))))
          wsClient.expectMessage(ByteString("Binary message received: abcdef"))

          wsClient.sendMessage(BinaryMessage(ByteString("123")))
          wsClient.expectMessage(ByteString("Binary message received: 123"))

          wsClient.sendCompletion()
          wsClient.expectCompletion()
        }
    }
    "fail flow with RuntimeException when received a text message while configured for binary messages" in {
      val wsClient = WSProbe()

      WS("http://localhost/", wsClient.flow) ~> strictBinaryWebsocketRoute ~>
        check {
          wsClient.sendMessage(TextMessage("Peter"))
          val error = wsClient.inProbe.expectSubscriptionAndError()
          error.getClass should be(classOf[RuntimeException])
          error.getMessage should be("Received TextMessage while expecting BinaryMessage.")
        }
    }
    "fail flow with TimeoutException when a binary message cannot be consumed within the timeout" in {
      val wsClient = WSProbe()

      WS("http://localhost/", wsClient.flow) ~> strictBinaryWebsocketRoute(1.millisecond) ~>
        check {
          isWebSocketUpgrade shouldEqual true

          wsClient.sendMessage(BinaryMessage(Source.repeat(ByteString("a"))))
          val error = wsClient.inProbe.expectSubscriptionAndError()
          error.getClass should be(classOf[TimeoutException])
        }
    }
  }

  def websocketRoute = handleWebSocketMessages(greeter)
  def websocketBuilder(build: WebSocketResponseBuilder[Message] ⇒ HttpResponse) = handleWsMessages(build)
  def strictWebsocketRoute(implicit timeout: FiniteDuration = 1.second) =
    handleWsMessages(builder ⇒ builder.toStrictTimeout(timeout).handleWith(strictGreeter))
  def strictTextWebsocketRoute(implicit timeout: FiniteDuration = 1.second) =
    handleWsMessages(builder ⇒ builder.only[TextMessage].toStrictTimeout(timeout).handleWith(strictTextGreeter))
  def strictBinaryWebsocketRoute(implicit timeout: FiniteDuration = 1.second) =
    handleWsMessages(builder ⇒ builder.only[BinaryMessage].toStrictTimeout(timeout).handleWith(strictBinaryGreeter))
  def websocketMultipleProtocolRoute =
    handleWebSocketMessagesForProtocol(echo, "echo") ~
      handleWebSocketMessagesForProtocol(greeter, "greeter")
  def strictWebsocketMultipleProtocolRoute(implicit timeout: FiniteDuration = 1.second) =
    handleWsMessages(builder ⇒ builder.toStrictTimeout(timeout).forProtocol("echo").handleWith(strictEcho)) ~
      handleWsMessages(builder ⇒ builder.forProtocol("greeter").toStrictTimeout(timeout).handleWith(strictGreeter))

  def greeter: Flow[Message, Message, Any] =
    Flow[Message].mapConcat {
      case tm: TextMessage ⇒ TextMessage(Source.single("Hello ") ++ tm.textStream ++ Source.single("!")) :: Nil
      case bm: BinaryMessage ⇒ // ignore binary messages
        bm.dataStream.runWith(Sink.ignore)
        Nil
    }

  def strictGreeter: Flow[StrictMessage, Message, Any] =
    Flow[StrictMessage].mapConcat {
      case TextMessage.Strict(text)     ⇒ TextMessage("Hello " + text + "!") :: Nil
      case BinaryMessage.Strict(binary) ⇒ BinaryMessage(ByteString("Binary message received: ") ++ binary) :: Nil
    }

  def strictTextGreeter: Flow[TextMessage.Strict, Message, Any] =
    Flow[StrictMessage].mapConcat {
      case TextMessage.Strict(text) ⇒ TextMessage("Hello " + text + "!") :: Nil
    }

  def strictBinaryGreeter: Flow[BinaryMessage.Strict, Message, Any] =
    Flow[StrictMessage].mapConcat {
      case BinaryMessage.Strict(binary) ⇒ BinaryMessage(ByteString("Binary message received: ") ++ binary) :: Nil
    }

  def echo: Flow[Message, Message, Any] =
    Flow[Message]
      .buffer(1, OverflowStrategy.backpressure) // needed because a noop flow hasn't any buffer that would start processing

  def strictEcho: Flow[StrictMessage, Message, Any] =
    Flow[StrictMessage].map(_.asInstanceOf[Message])
      .buffer(1, OverflowStrategy.backpressure) // needed because a noop flow hasn't any buffer that would start processing
}
