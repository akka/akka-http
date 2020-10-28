/*
 * Copyright (C) 2018-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.NotUsed
import akka.event.Logging
import akka.http.impl.engine.http2.FrameEvent._
import akka.http.impl.engine.http2.Http2Protocol.ErrorCode
import akka.http.impl.engine.http2.Http2Protocol.FrameType
import akka.http.impl.engine.http2.Http2Protocol.SettingIdentifier
import akka.http.impl.engine.ws.ByteStringSinkProbe
import akka.http.impl.util.{AkkaSpecWithMaterializer, LogByteStringTools}
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.HttpEntity.Strict
import akka.http.scaladsl.model.HttpMethods.GET
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.settings.ClientConnectionSettings
import akka.stream.Attributes
import akka.stream.Attributes.LogLevels
import akka.stream.scaladsl.{BidiFlow, Flow, Sink, Source}
import akka.stream.testkit.{TestPublisher, TestSubscriber}
import akka.util.ByteString
import org.scalatest.concurrent.Eventually

import scala.concurrent.duration._

/**
 * This tests the http2 client protocol logic.
 *
 * Tests typically:
 * * provide outgoing application-level requests
 * * if applicable: validate the constructed outgoing frames
 * * if applicable: provide response frames
 * * validate the produced application-level responses
 */
class Http2ClientSpec extends AkkaSpecWithMaterializer("""
    akka.http.server.remote-address-header = on
    akka.http.server.http2.log-frames = on
    akka.http.client.http2.log-frames = on
  """)
  with WithInPendingUntilFixed with Eventually {
  override def failOnSevereMessages: Boolean = true

  "The Http/2 client implementation" should {
    "support simple round-trips" should {
      abstract class SimpleRequestResponseRoundtripSetup extends TestSetup with NetProbes with Http2FrameHpackSupport {
        def requestResponseRoundtrip(
          streamId:         Int,
          request:          HttpRequest,
          expectedHeaders:  Seq[(String, String)],
          response:         Seq[FrameEvent],
          expectedResponse: HttpResponse
        ): Unit = {
          emitRequest(streamId, request)

          expectDecodedResponseHEADERSPairs(streamId) should contain theSameElementsAs (expectedHeaders.filter(_._1 != "date"))
          response.foreach(sendFrame)

          expectResponse() shouldBe expectedResponse
        }
      }

      "GET request in one HEADERS frame" in new SimpleRequestResponseRoundtripSetup {
        requestResponseRoundtrip(
          streamId = 1,
          request = HttpRequest(uri = "https://www.example.com/"),
          expectedHeaders = Seq(
            ":method" -> "GET",
            ":scheme" -> "https",
            ":authority" -> "www.example.com",
            ":path" -> "/",
            "content-length" -> "0"
          ),
          response = Seq(
            // TODO shouldn't this produce an error since stream '1' is already the outgoing stream?
            HeadersFrame(streamId = 1, endStream = true, endHeaders = true, HPackSpecExamples.C61FirstResponseWithHuffman, None)
          ),
          expectedResponse =
            HPackSpecExamples.FirstResponse
              .withEntity(Strict(ContentTypes.NoContentType, ByteString.empty))
        )
      }
      "GOAWAY when the response has an invalid headers frame" in new TestSetup with NetProbes {
        val streamId = 0x1
        emitRequest(streamId, HttpRequest(uri = "http://www.example.com/"))
        expectFrame() shouldBe a[HeadersFrame]

        val headerBlock = hex"00 00 01 01 05 00 00 00 01 40"
        sendFrame(HeadersFrame(streamId, endStream = true, endHeaders = true, headerBlock, None))

        val (_, error) = expectGOAWAY(1)
        error should ===(ErrorCode.COMPRESSION_ERROR)

        // TODO we'd expect an error response here I think? We don't get any reply though...
      }
      "GOAWAY when the response to a second request on different stream has an invalid headers frame" in new SimpleRequestResponseRoundtripSetup {
        requestResponseRoundtrip(
          streamId = 1,
          request = HttpRequest(uri = "https://www.example.com/"),
          expectedHeaders = Seq(
            ":method" -> "GET",
            ":scheme" -> "https",
            ":authority" -> "www.example.com",
            ":path" -> "/",
            "content-length" -> "0"
          ),
          response = Seq(
            HeadersFrame(streamId = 1, endStream = true, endHeaders = true, HPackSpecExamples.C61FirstResponseWithHuffman, None)
          ),
          expectedResponse =
            HPackSpecExamples.FirstResponse
              .withEntity(Strict(ContentTypes.NoContentType, ByteString.empty))
        )

        emitRequest(3, HttpRequest(uri = "https://www.example.com/"))
        expectFrame() shouldBe a[HeadersFrame]

        val incorrectHeaderBlock = hex"00 00 01 01 05 00 00 00 01 40"
        sendHEADERS(3, endStream = true, endHeaders = true, headerBlockFragment = incorrectHeaderBlock)

        val (_, errorCode) = expectGOAWAY(3)
        errorCode should ===(ErrorCode.COMPRESSION_ERROR)
      }
      "Three consecutive GET requests" in new SimpleRequestResponseRoundtripSetup {
        import akka.http.scaladsl.model.headers.CacheDirectives._
        import headers.`Cache-Control`
        val requestHeaders = Seq(
          ":method" -> "GET",
          ":scheme" -> "https",
          ":authority" -> "www.example.com",
          ":path" -> "/",
          "content-length" -> "0"
        )
        requestResponseRoundtrip(
          streamId = 1,
          request = HttpRequest(GET, "https://www.example.com/"),
          expectedHeaders = requestHeaders,
          response = Seq(
            HeadersFrame(streamId = 1, endStream = true, endHeaders = true, HPackSpecExamples.C61FirstResponseWithHuffman, None)
          ),
          expectedResponse = HPackSpecExamples.FirstResponse
            .withEntity(Strict(ContentTypes.NoContentType, ByteString.empty))
        )
        requestResponseRoundtrip(
          streamId = 3,
          request = HttpRequest(GET, uri = "https://www.example.com/", Vector(`Cache-Control`(`no-cache`))),
          expectedHeaders = requestHeaders :+ ("cache-control" -> "no-cache"),
          response = Seq(
            HeadersFrame(streamId = 3, endStream = true, endHeaders = true, HPackSpecExamples.C62SecondResponseWithHuffman, None)
          ),
          expectedResponse = HPackSpecExamples.SecondResponse
            .withEntity(Strict(ContentTypes.NoContentType, ByteString.empty))
        )
        requestResponseRoundtrip(
          streamId = 5,
          request = HttpRequest(HttpMethods.GET, "https://www.example.com/", Vector(RawHeader("custom-key", "custom-value"))),
          expectedHeaders = requestHeaders :+ ("custom-key" -> "custom-value"),
          response = Seq(
            HeadersFrame(streamId = 5, endStream = true, endHeaders = true, HPackSpecExamples.C63ThirdResponseWithHuffman, None)
          ),
          expectedResponse = HPackSpecExamples.ThirdResponseModeled
            .withEntity(Strict(ContentTypes.NoContentType, ByteString.empty))
        )
      }
    }

    "respect settings" should {
      "received SETTINGS_MAX_CONCURRENT_STREAMS should limit the number of outgoing streams" in new TestSetup(
        Setting(SettingIdentifier.SETTINGS_MAX_CONCURRENT_STREAMS, 3)
      ) with NetProbes {
        val request = HttpRequest(uri = "https://www.example.com/")
        // server set SETTINGS_MAX_CONCURRENT_STREAMS=5 so an attempt from the client to open 6 streams
        // should only produce 5 frames
        emitRequest(1, request)
        emitRequest(3, request)
        emitRequest(5, request)
        emitRequest(7, request)

        // expect frames for 1 3 and 5
        expectFrame().asInstanceOf[HeadersFrame].streamId shouldBe (1)
        expectFrame().asInstanceOf[HeadersFrame].streamId shouldBe (3)
        expectFrame().asInstanceOf[HeadersFrame].streamId shouldBe (5)
        // expect silence on the line
        expectNoBytes(100.millis)

        // close 1 and 3
        sendFrame(HeadersFrame(streamId = 1, endStream = true, endHeaders = true, HPackSpecExamples.C61FirstResponseWithHuffman, None))
        sendFrame(HeadersFrame(streamId = 3, endStream = true, endHeaders = true, HPackSpecExamples.C61FirstResponseWithHuffman, None))
        emitRequest(9, request)
        emitRequest(11, request)
        // expect 7 and 9 on the line
        expectFrame().asInstanceOf[HeadersFrame].streamId shouldBe (7)
        expectFrame().asInstanceOf[HeadersFrame].streamId shouldBe (9)
        expectNoBytes(100.millis)

        // close 5 7 9
        sendFrame(HeadersFrame(streamId = 5, endStream = true, endHeaders = true, HPackSpecExamples.C61FirstResponseWithHuffman, None))
        sendFrame(HeadersFrame(streamId = 7, endStream = true, endHeaders = true, HPackSpecExamples.C61FirstResponseWithHuffman, None))
        sendFrame(HeadersFrame(streamId = 9, endStream = true, endHeaders = true, HPackSpecExamples.C61FirstResponseWithHuffman, None))
        emitRequest(13, request)
        // expect 11 the line
        expectFrame().asInstanceOf[HeadersFrame].streamId shouldBe (11)
        expectFrame().asInstanceOf[HeadersFrame].streamId shouldBe (13)
      }

    }

  }

  protected /* To make ByteFlag warnings go away */ abstract class TestSetupWithoutHandshake {
    implicit def ec = system.dispatcher

    lazy val responseIn = TestSubscriber.probe[HttpResponse]()
    lazy val requestOut = TestPublisher.probe[HttpRequest]()

    def netFlow: Flow[ByteString, ByteString, NotUsed]

    // hook to modify client, for example add attributes
    def modifyClient(client: BidiFlow[HttpRequest, ByteString, ByteString, HttpResponse, NotUsed]) = client

    // hook to modify server settings
    def settings = ClientConnectionSettings(system)

    final def theClient: BidiFlow[ByteString, HttpResponse, HttpRequest, ByteString, NotUsed] =
      modifyClient(Http2Blueprint.clientStack(settings, system.log))
        .atop(LogByteStringTools.logByteStringBidi("network-plain-text").addAttributes(Attributes(LogLevels(Logging.DebugLevel, Logging.DebugLevel, Logging.DebugLevel))))
        .reversed

    netFlow
      .join(theClient)
      .join(Flow.fromSinkAndSource(Sink.fromSubscriber(responseIn), Source.fromPublisher(requestOut)))
      .withAttributes(Attributes.inputBuffer(1, 1))
      .run()

    def expectResponse(): HttpResponse = responseIn.requestNext().removeAttribute(Http2.streamId)
    def expectResponseRaw(): HttpResponse = responseIn.requestNext() // TODO, make it so that internal headers are not listed in `headers` etc?
    def emitRequest(streamId: Int, request: HttpRequest): Unit =
      requestOut.sendNext(request.addAttribute(Http2.streamId, streamId))
  }

  /** Basic TestSetup that has already passed the exchange of the connection preface */
  abstract class TestSetup(initialServerSettings: Setting*) extends TestSetupWithoutHandshake with NetProbes with Http2FrameSending {
    toNet.expectBytes(Http2Protocol.ClientConnectionPreface)
    expectFrame() shouldBe a[SettingsFrame]

    sendFrame(SettingsFrame(initialServerSettings))
    expectSettingsAck()
  }

  /** Provides the net flow as `toNet` and `fromNet` probes for manual stream interaction */
  trait NetProbes extends TestSetupWithoutHandshake with Http2FrameProbeDelegator {
    lazy val framesOut: Http2FrameProbe = Http2FrameProbe()
    override def frameProbeDelegate: Http2FrameProbe = framesOut
    lazy val toNet: ByteStringSinkProbe = framesOut.plainDataProbe
    lazy val fromNet: TestPublisher.Probe[ByteString] = TestPublisher.probe[ByteString]()

    override def netFlow: Flow[ByteString, ByteString, NotUsed] =
      Flow.fromSinkAndSource(toNet.sink, Source.fromPublisher(fromNet))

    def expectGracefulCompletion(): Unit = {
      responseIn.expectComplete()
      toNet.expectComplete()
    }

    def sendBytes(bytes: ByteString): Unit = fromNet.sendNext(bytes)
  }
}
