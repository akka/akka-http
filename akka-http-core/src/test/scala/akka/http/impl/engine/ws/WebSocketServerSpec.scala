/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.ws

import akka.http.scaladsl.model.ws._
import akka.stream.scaladsl.{ Flow, Keep, Sink, Source }
import akka.stream.testkit.Utils
import akka.util.ByteString
import org.scalatest.{ FreeSpec, Matchers }
import akka.http.impl.engine.server.HttpServerTestSetupBase

import scala.concurrent.duration._

class WebSocketServerSpec extends FreeSpec with Matchers with WithMaterializerSpec { spec =>

  "The server-side WebSocket integration should" - {
    "establish a websocket connection when the user requests it" - {
      "when user handler instantly tries to send messages" in Utils.assertAllStagesStopped {
        new TestSetup {
          send(
            """GET /chat HTTP/1.1
              |Host: server.example.com
              |Upgrade: websocket
              |Connection: Upgrade
              |Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==
              |Origin: http://example.com
              |Sec-WebSocket-Version: 13
              |
              |""")

          val request = expectRequest()
          val upgrade = request.header[UpgradeToWebSocket]
          upgrade.isDefined shouldBe true

          val source =
            Source(List(1, 2, 3, 4, 5)).map(num => TextMessage.Strict(s"Message $num"))
          val handler = Flow.fromSinkAndSourceMat(Sink.ignore, source)(Keep.none)
          val response = upgrade.get.handleMessages(handler)
          responses.sendNext(response)

          expectResponseWithWipedDate(
            """HTTP/1.1 101 Switching Protocols
              |Upgrade: websocket
              |Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=
              |Server: akka-http/test
              |Date: XXXX
              |Connection: upgrade
              |
              |""")

          expectWSFrame(Protocol.Opcode.Text, ByteString("Message 1"), fin = true)
          expectWSFrame(Protocol.Opcode.Text, ByteString("Message 2"), fin = true)
          expectWSFrame(Protocol.Opcode.Text, ByteString("Message 3"), fin = true)
          expectWSFrame(Protocol.Opcode.Text, ByteString("Message 4"), fin = true)
          expectWSFrame(Protocol.Opcode.Text, ByteString("Message 5"), fin = true)
          expectWSCloseFrame(Protocol.CloseCodes.Regular)

          sendWSCloseFrame(Protocol.CloseCodes.Regular, mask = true)
          closeNetworkInput()
          expectNetworkClose()
        }
      }
      "for echoing user handler" in Utils.assertAllStagesStopped {
        new TestSetup {

          send(
            """GET /echo HTTP/1.1
              |Host: server.example.com
              |Upgrade: websocket
              |Connection: Upgrade
              |Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==
              |Origin: http://example.com
              |Sec-WebSocket-Version: 13
              |
              |""")

          val request = expectRequest()
          val upgrade = request.header[UpgradeToWebSocket]
          upgrade.isDefined shouldBe true

          val response = upgrade.get.handleMessages(Flow[Message]) // simple echoing
          responses.sendNext(response)

          expectResponseWithWipedDate(
            """HTTP/1.1 101 Switching Protocols
              |Upgrade: websocket
              |Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=
              |Server: akka-http/test
              |Date: XXXX
              |Connection: upgrade
              |
              |""")

          sendWSFrame(Protocol.Opcode.Text, ByteString("Message 1"), fin = true, mask = true)
          expectWSFrame(Protocol.Opcode.Text, ByteString("Message 1"), fin = true)
          sendWSFrame(Protocol.Opcode.Text, ByteString("Message 2"), fin = true, mask = true)
          expectWSFrame(Protocol.Opcode.Text, ByteString("Message 2"), fin = true)
          sendWSFrame(Protocol.Opcode.Text, ByteString("Message 3"), fin = true, mask = true)
          expectWSFrame(Protocol.Opcode.Text, ByteString("Message 3"), fin = true)
          sendWSFrame(Protocol.Opcode.Text, ByteString("Message 4"), fin = true, mask = true)
          expectWSFrame(Protocol.Opcode.Text, ByteString("Message 4"), fin = true)
          sendWSFrame(Protocol.Opcode.Text, ByteString("Message 5"), fin = true, mask = true)
          expectWSFrame(Protocol.Opcode.Text, ByteString("Message 5"), fin = true)

          sendWSCloseFrame(Protocol.CloseCodes.Regular, mask = true)
          expectWSCloseFrame(Protocol.CloseCodes.Regular)

          closeNetworkInput()
          expectNetworkClose()
        }
      }
    }
    "send Ping keep-alive heartbeat" - {
      "on idle websocket connection" in Utils.assertAllStagesStopped {
        new TestSetup {

          override def settings = {
            val defaults = super.settings.websocketSettings
            super.settings.withWebsocketSettings(defaults
              .withPeriodicKeepAliveMode("ping")
              .withPeriodicKeepAliveMaxIdle(100.millis)
            )
          }

          send(
            """GET /echo HTTP/1.1
              |Host: server.example.com
              |Upgrade: websocket
              |Connection: Upgrade
              |Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==
              |Origin: http://example.com
              |Sec-WebSocket-Version: 13
              |
              |""")

          val request = expectRequest()
          val upgrade = request.header[UpgradeToWebSocket]

          val handler = Flow.fromSinkAndSourceCoupled(Sink.ignore, Source.maybe[Message])

          // since the handler is not doing anything, we expect the server to start sending Ping frames transparently
          val response = upgrade.get.handleMessages(handler)
          responses.sendNext(response)

          expectResponseWithWipedDate(
            """HTTP/1.1 101 Switching Protocols
              |Upgrade: websocket
              |Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=
              |Server: akka-http/test
              |Date: XXXX
              |Connection: upgrade
              |
              |""")

          expectWSFrame(Protocol.Opcode.Ping, ByteString.empty, fin = true)

          sendWSCloseFrame(Protocol.CloseCodes.Regular, mask = true)
          expectWSCloseFrame(Protocol.CloseCodes.Regular)

          closeNetworkInput()
          expectNetworkClose()
        }
      }
    }
    "prevent the selection of an unavailable subprotocol" in pending
    "reject invalid WebSocket handshakes" - {
      "missing `Upgrade: websocket` header" in pending
      "missing `Connection: upgrade` header" in pending
      "missing `Sec-WebSocket-Key header" in pending
      "`Sec-WebSocket-Key` with wrong amount of base64 encoded data" in pending
      "missing `Sec-WebSocket-Version` header" in pending
      "unsupported `Sec-WebSocket-Version`" in pending
    }
  }

  class TestSetup extends HttpServerTestSetupBase with WSTestSetupBase {
    implicit def system = spec.system
    implicit def materializer = spec.materializer

    def expectBytes(length: Int): ByteString = netOut.expectBytes(length)
    def expectBytes(bytes: ByteString): Unit = netOut.expectBytes(bytes)
  }
}
