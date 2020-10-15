/*
 * Copyright (C) 2018-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.NotUsed
import akka.event.Logging
import akka.http.impl.engine.http2.FrameEvent._
import akka.http.impl.engine.http2.Http2Protocol.ErrorCode
import akka.http.impl.util.{ AkkaSpecWithMaterializer, LogByteStringTools }
import akka.http.scaladsl.Http2
import akka.http.scaladsl.model.HttpEntity.Strict
import akka.http.scaladsl.model._
import akka.http.scaladsl.settings.ClientConnectionSettings
import akka.stream.Attributes
import akka.stream.Attributes.LogLevels
import akka.stream.scaladsl.{ BidiFlow, Flow, Sink, Source }
import akka.stream.testkit.{ TestPublisher, TestSubscriber }
import akka.util.ByteString
import org.scalatest.concurrent.Eventually

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
          request = HttpRequest(uri = "http://www.example.com/"),
          expectedHeaders = Seq(
            ":method" -> "GET",
            // TODO check if this makes sense?
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
        val requestStreamId = 0x1
        emitRequest(requestStreamId, HttpRequest(uri = "http://www.example.com/"))
        expectFrame() shouldBe a[HeadersFrame]

        val responseStreamId = 0x2
        val headerBlock = hex"00 00 01 01 05 00 00 00 01 40"
        sendFrame(HeadersFrame(responseStreamId, endStream = true, endHeaders = true, headerBlock, None))

        val (_, error) = expectGOAWAY(1)
        error should ===(ErrorCode.COMPRESSION_ERROR)

        // TODO we'd expect an error response here I think?
      }
      "GOAWAY when the response to a second request on different stream has an invalid headers frame" in new SimpleRequestResponseRoundtripSetup {
        requestResponseRoundtrip(
          streamId = 1,
          request = HttpRequest(uri = "http://www.example.com/"),
          expectedHeaders = Seq(
            ":method" -> "GET",
            // TODO check if this makes sense?
            ":scheme" -> "https",
            ":authority" -> "www.example.com",
            ":path" -> "/",
            "content-length" -> "0"
          ),
          response = Seq(
            // TODO shouldn't this produce an error since stream '1' is already the outgoing stream?
            HeadersFrame(streamId = 2, endStream = true, endHeaders = true, HPackSpecExamples.C61FirstResponseWithHuffman, None)
          ),
          expectedResponse =
            HPackSpecExamples.FirstResponse
              .withEntity(Strict(ContentTypes.NoContentType, ByteString.empty))
        )

        emitRequest(3, HttpRequest(uri = "http://www.example.com/"))
        expectFrame() shouldBe a[HeadersFrame]

        val incorrectHeaderBlock = hex"00 00 01 01 05 00 00 00 01 40"
        sendHEADERS(4, endStream = true, endHeaders = true, headerBlockFragment = incorrectHeaderBlock)

        // TODO shouldn't this be '3'?
        val (_, errorCode) = expectGOAWAY(1)
        errorCode should ===(ErrorCode.COMPRESSION_ERROR)
      }
    }
  }

  protected /* To make ByteFlag warnings go away */ abstract class TestSetupWithoutHandshake {
    implicit def ec = system.dispatcher

    lazy val responseIn = TestSubscriber.probe[HttpResponse]()
    lazy val requestOut = TestPublisher.probe[HttpRequest]()

    def netFlow: Flow[ByteString, ByteString, NotUsed]

    // hook to modify server, for example add attributes
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
  abstract class TestSetup extends TestSetupWithoutHandshake with NetProbes with Http2FrameSending {
    toNet.expectBytes(Http2Protocol.ClientConnectionPreface)
    expectFrame() shouldBe a[SettingsFrame]

    sendFrame(SettingsFrame(Nil))
    expectSettingsAck()
  }

  /** Provides the net flow as `toNet` and `fromNet` probes for manual stream interaction */
  trait NetProbes extends TestSetupWithoutHandshake with Http2FrameProbeDelegator {
    lazy val framesOut: Http2FrameProbe = Http2FrameProbe()
    override def frameProbeDelegate: Http2FrameProbe = framesOut
    lazy val toNet = framesOut.plainDataProbe
    lazy val fromNet = TestPublisher.probe[ByteString]()

    override def netFlow: Flow[ByteString, ByteString, NotUsed] =
      Flow.fromSinkAndSource(toNet.sink, Source.fromPublisher(fromNet))

    def expectGracefulCompletion(): Unit = {
      responseIn.expectComplete()
      toNet.expectComplete()
    }

    def sendBytes(bytes: ByteString): Unit = fromNet.sendNext(bytes)
  }
}
