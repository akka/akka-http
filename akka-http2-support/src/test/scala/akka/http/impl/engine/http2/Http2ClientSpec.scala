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
import akka.http.impl.util.{ AkkaSpecWithMaterializer, LogByteStringTools }
import akka.http.scaladsl.client.RequestBuilding.Get
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.HttpEntity.{ Chunk, Chunked, LastChunk }
import akka.http.scaladsl.model.HttpMethods.GET
import akka.http.scaladsl.model.headers.CacheDirectives._
import akka.http.scaladsl.model.headers.{ RawHeader, `Access-Control-Allow-Origin`, `Cache-Control` }
import akka.http.scaladsl.settings.ClientConnectionSettings
import akka.stream.Attributes
import akka.stream.Attributes.LogLevels
import akka.stream.scaladsl.{ BidiFlow, Flow, Sink, Source }
import akka.stream.testkit.{ TestPublisher, TestSubscriber }
import akka.util.ByteString
import org.scalatest.concurrent.Eventually

import scala.collection.immutable
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
    akka.http.client.http2.log-frames = on
  """)
  with WithInPendingUntilFixed with Eventually {

  override implicit val patience = PatienceConfig(5.seconds, 5.seconds)
  override def failOnSevereMessages: Boolean = true

  "The Http/2 client implementation" should {
    "support simple round-trips" should {
      abstract class SimpleRequestResponseRoundtripSetup extends TestSetup with NetProbes {
        def requestResponseRoundtrip(
          streamId:         Int,
          request:          HttpRequest,
          expectedHeaders:  Seq[(String, String)],
          response:         Seq[FrameEvent],
          expectedResponse: HttpResponse
        ): Unit = {
          user.emitRequest(streamId, request)

          network.expectDecodedResponseHEADERSPairs(streamId) should contain theSameElementsAs (expectedHeaders.filter(_._1 != "date"))
          response.foreach(network.sendFrame)

          val receivedResponse = user.expectResponse()
          receivedResponse.status shouldBe expectedResponse.status
          receivedResponse.headers shouldBe expectedResponse.headers
          receivedResponse.entity.contentType shouldBe expectedResponse.entity.contentType
          receivedResponse.entity.dataBytes.runFold(ByteString())(_ ++ _).futureValue shouldBe expectedResponse.entity.dataBytes.runFold(ByteString())(_ ++ _).futureValue
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
          expectedResponse = HPackSpecExamples.FirstResponse
        )
      }

      "GOAWAY when the response has an invalid headers frame" in new TestSetup with NetProbes {
        val streamId = 0x1
        user.emitRequest(streamId, HttpRequest(uri = "http://www.example.com/"))
        network.expect[HeadersFrame]()

        val headerBlock = hex"00 00 01 01 05 00 00 00 01 40"
        network.sendFrame(HeadersFrame(streamId, endStream = true, endHeaders = true, headerBlock, None))

        val (_, error) = network.expectGOAWAY(1)
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
          expectedResponse = HPackSpecExamples.FirstResponse
        )

        user.emitRequest(3, HttpRequest(uri = "https://www.example.com/"))
        network.expect[HeadersFrame]()

        val incorrectHeaderBlock = hex"00 00 01 01 05 00 00 00 01 40"
        network.sendHEADERS(3, endStream = true, endHeaders = true, headerBlockFragment = incorrectHeaderBlock)

        val (_, errorCode) = network.expectGOAWAY(3)
        errorCode should ===(ErrorCode.COMPRESSION_ERROR)
      }

      "GOAWAY when the response has headers mid stream" in new TestSetup with NetProbes {
        val streamId = 0x1
        user.emitRequest(streamId, Get("/"))
        network.expectDecodedHEADERS(streamId, endStream = true)

        network.sendHEADERS(streamId, endStream = false, Seq(
          RawHeader(":status", "200"),
          RawHeader("content-type", "application/octet-stream")
        ))

        val response = user.expectResponse()
        response.entity shouldBe a[Chunked]

        network.sendDATA(streamId, endStream = false, ByteString("asdf"))
        network.expectFrameFlagsStreamIdAndPayload(FrameType.WINDOW_UPDATE)

        network.sendHEADERS(streamId, endStream = false, Seq(RawHeader("X-mid-stream", "badvalue")))

        val goAwayFrame = network.expect[GoAwayFrame]()
        goAwayFrame.errorCode should ===(ErrorCode.PROTOCOL_ERROR)
        goAwayFrame.debug.utf8String should ===("Got unexpected mid-stream HEADERS frame")
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
        )
        requestResponseRoundtrip(
          streamId = 3,
          request = HttpRequest(GET, uri = "https://www.example.com/", Vector(`Cache-Control`(`no-cache`))),
          expectedHeaders = requestHeaders :+ ("cache-control" -> "no-cache"),
          response = Seq(
            HeadersFrame(streamId = 3, endStream = true, endHeaders = true, HPackSpecExamples.C62SecondResponseWithHuffman, None)
          ),
          expectedResponse = HPackSpecExamples.SecondResponse
        )
        requestResponseRoundtrip(
          streamId = 5,
          request = HttpRequest(HttpMethods.GET, "https://www.example.com/", Vector(RawHeader("custom-key", "custom-value"))),
          expectedHeaders = requestHeaders :+ ("custom-key" -> "custom-value"),
          response = Seq(
            HeadersFrame(streamId = 5, endStream = true, endHeaders = true, HPackSpecExamples.C63ThirdResponseWithHuffman, None)
          ),
          expectedResponse = HPackSpecExamples.ThirdResponseModeled
        )
      }

      "accept response with one HEADERS and one CONTINUATION frame" in new TestSetup with NetProbes {
        user.emitRequest(0x1, Get("https://www.example.com/"))
        network.expect[HeadersFrame]()

        val headerBlock = HPackSpecExamples.C61FirstResponseWithHuffman
        val fragment1 = headerBlock.take(8) // must be grouped by octets
        val fragment2 = headerBlock.drop(8)
        network.sendHEADERS(0x1, endStream = true, endHeaders = false, fragment1)
        network.sendCONTINUATION(0x1, endHeaders = true, fragment2)
        user.expectResponse().headers should be(HPackSpecExamples.FirstResponse.headers)
      }

      "accept response with one HEADERS and two CONTINUATION frames" in new TestSetup with NetProbes {
        user.emitRequest(0x1, Get("https://www.example.com/"))
        network.expect[HeadersFrame]()

        val headerBlock = HPackSpecExamples.C61FirstResponseWithHuffman
        val fragment1 = headerBlock.take(8) // must be grouped by octets
        val fragment2 = headerBlock.drop(8).take(8)
        val fragment3 = headerBlock.drop(16)
        network.sendHEADERS(0x1, endStream = true, endHeaders = false, fragment1)
        network.sendCONTINUATION(0x1, endHeaders = false, fragment2)
        network.sendCONTINUATION(0x1, endHeaders = true, fragment3)
        user.expectResponse().headers should be(HPackSpecExamples.FirstResponse.headers)
      }

      "automatically add `date` header" in new TestSetup with NetProbes {
        user.emitRequest(0x1, Get("https://www.example.com/"))
        network.expectDecodedHEADERS(0x1, endStream = true).headers.exists(_.is("date"))
      }

      "parse headers to modeled headers" in new TestSetup with NetProbes {
        user.emitRequest(0x1, Get("https://www.example.com/"))
        network.expect[HeadersFrame]()

        network.sendHEADERS(0x1, true, Seq(
          RawHeader(":status", "401"),
          RawHeader("cache-control", "no-cache"),
          RawHeader("cache-control", "max-age=1000"),
          RawHeader("access-control-allow-origin", "*")
        ))

        val response = user.expectResponse()
        response.status should be(StatusCodes.Unauthorized)
        response.headers should contain(`Cache-Control`(`no-cache`))
        response.headers should contain(`Cache-Control`(`max-age`(1000)))
        response.headers should contain(`Access-Control-Allow-Origin`.`*`)
      }

      "acknowledge change to SETTINGS_HEADER_TABLE_SIZE in next HEADER frame" in new TestSetup with NetProbes {
        network.sendSETTING(SettingIdentifier.SETTINGS_HEADER_TABLE_SIZE, 8192)
        network.expectSettingsAck()

        user.emitRequest(0x1, Get("/"))
        val headerPayload = network.expectHeaderBlock(1)

        // Dynamic Table Size Update (https://tools.ietf.org/html/rfc7541#section-6.3) is
        //
        // 0   1   2   3   4   5   6   7
        // +---+---+---+---+---+---+---+---+
        // | 0 | 0 | 1 |   Max size (5+)   |
        // +---+---------------------------+
        //
        // 8192 is 10000000000000 = 14 bits, i.e. needs more than 4 bits
        // 8192 - 16 = 8176 = 111111 1110000
        // 001 11111 = 63
        // 1 1110000 = 225
        // 0 0111111 = 63

        val dynamicTableUpdateTo8192 = ByteString(63, 225, 63)
        headerPayload.take(3) shouldBe dynamicTableUpdateTo8192
      }
    }

    "respect flow-control" should {
      "accept window updates when done sending the request" in new TestSetup {
        user.emitRequest(0x1, Get("/"))
        network.expectDecodedHEADERS(0x1, endStream = true)

        // Server randomly sends a window update even though we're already done sending the request,
        // which may happen since window updating is asynchronous:
        network.sendWINDOW_UPDATE(0x1, 20)
        network.sendHEADERS(0x1, endStream = true, endHeaders = true, network.encodeHeaderPairs(Seq((":status", "200"))))
        user.expectResponse()
      }
    }

    "send settings" should {
      abstract class SettingsSetup extends TestSetupWithoutHandshake with NetProbes {
        def expectSetting(expected: Setting): Unit = {
          network.toNet.expectBytes(Http2Protocol.ClientConnectionPreface)
          network.expectSETTINGS().settings should contain(expected)
        }
      }

      "disable Push via SETTINGS_ENABLE_PUSH" in new SettingsSetup {
        expectSetting(Setting(SettingIdentifier.SETTINGS_ENABLE_PUSH, 0))
      }
    }

    "respect settings" should {
      "received SETTINGS_MAX_CONCURRENT_STREAMS should limit the number of outgoing streams" in new TestSetup(
        Setting(SettingIdentifier.SETTINGS_MAX_CONCURRENT_STREAMS, 3)
      ) with NetProbes {
        val request = HttpRequest(uri = "https://www.example.com/")
        // server set a very small SETTINGS_MAX_CONCURRENT_STREAMS, so an attempt from the
        // client to open more streams should backpressure
        user.emitRequest(1, request)
        user.emitRequest(3, request)
        user.emitRequest(5, request)
        user.emitRequest(7, request) // this emit succeeds but is buffered

        // expect frames for 1 3 and 5
        network.expect[HeadersFrame]().streamId shouldBe (1)
        network.expect[HeadersFrame]().streamId shouldBe (3)
        network.expect[HeadersFrame]().streamId shouldBe (5)
        // expect silence on the line
        network.expectNoBytes(100.millis)

        // close 1 and 3
        network.sendFrame(HeadersFrame(streamId = 1, endStream = true, endHeaders = true, HPackSpecExamples.C61FirstResponseWithHuffman, None))
        network.sendFrame(HeadersFrame(streamId = 3, endStream = true, endHeaders = true, HPackSpecExamples.C61FirstResponseWithHuffman, None))
        user.emitRequest(9, request)
        user.emitRequest(11, request)
        // expect 7 and 9 on the line
        network.expect[HeadersFrame]().streamId shouldBe (7)
        network.expect[HeadersFrame]().streamId shouldBe (9)
        network.expectNoBytes(100.millis)

        // close 5 7 9
        network.sendFrame(HeadersFrame(streamId = 5, endStream = true, endHeaders = true, HPackSpecExamples.C61FirstResponseWithHuffman, None))
        network.sendFrame(HeadersFrame(streamId = 7, endStream = true, endHeaders = true, HPackSpecExamples.C61FirstResponseWithHuffman, None))
        network.sendFrame(HeadersFrame(streamId = 9, endStream = true, endHeaders = true, HPackSpecExamples.C61FirstResponseWithHuffman, None))
        user.emitRequest(13, request)
        // expect 11 the line
        network.expect[HeadersFrame]().streamId shouldBe (11)
        network.expect[HeadersFrame]().streamId shouldBe (13)
      }
      "increasing SETTINGS_MAX_CONCURRENT_STREAMS should flush backpressured outgoing streams" in new TestSetup(
        Setting(SettingIdentifier.SETTINGS_MAX_CONCURRENT_STREAMS, 2)
      ) with NetProbes {
        val request = HttpRequest(uri = "https://www.example.com/")
        user.emitRequest(1, request)
        user.emitRequest(3, request)
        user.emitRequest(5, request) // this emit succeeds but is buffered

        // expect frames for 1 and 3
        network.expect[HeadersFrame]().streamId shouldBe (1)
        network.expect[HeadersFrame]().streamId shouldBe (3)
        // expect silence on the line
        network.expectNoBytes(100.millis)

        // Increasing the capacity...
        network.sendSETTING(SettingIdentifier.SETTINGS_MAX_CONCURRENT_STREAMS, 4)
        network.expectSettingsAck()

        // ... should let frame 5 pass
        network.expect[HeadersFrame]().streamId shouldBe (5)
      }
      "decreasing SETTINGS_MAX_CONCURRENT_STREAMS should keep backpressure outgoing streams until limit is respected" in new TestSetup(
        Setting(SettingIdentifier.SETTINGS_MAX_CONCURRENT_STREAMS, 3)
      ) with NetProbes {
        val request = HttpRequest(uri = "https://www.example.com/")
        user.emitRequest(1, request)
        user.emitRequest(3, request)
        user.emitRequest(5, request)
        user.emitRequest(7, request) // this emit succeeds but is buffered

        // expect frames for 1 3 and 5
        network.expect[HeadersFrame]().streamId shouldBe (1)
        network.expect[HeadersFrame]().streamId shouldBe (3)
        network.expect[HeadersFrame]().streamId shouldBe (5)
        network.expectNoBytes(100.millis)

        // Decreasing the capacity...
        network.sendSETTING(SettingIdentifier.SETTINGS_MAX_CONCURRENT_STREAMS, 2)
        network.expectSettingsAck()

        network.expectNoBytes(100.millis)

        network.sendFrame(HeadersFrame(streamId = 1, endStream = true, endHeaders = true, HPackSpecExamples.C61FirstResponseWithHuffman, None))
        network.expectNoBytes(100.millis)

        // Once 1 and 3 are closed, there'll be capacity for 7 to go through
        network.sendFrame(HeadersFrame(streamId = 3, endStream = true, endHeaders = true, HPackSpecExamples.C61FirstResponseWithHuffman, None))
        network.expect[HeadersFrame]().streamId shouldBe (7)
        // .. but not enough capacity for 9
        user.emitRequest(9, request)
        network.expectNoBytes(100.millis)

      }
    }

    "support stream support for receiving response entity data" should {
      abstract class WaitingForResponseSetup extends TestSetup with NetProbes {
        val streamId = 0x1
        user.emitRequest(streamId, Get("/"))
        network.expectDecodedHEADERS(streamId, endStream = true)
      }
      "support trailing headers for responses" in new WaitingForResponseSetup {
        network.sendHEADERS(streamId, endStream = false, Seq(
          RawHeader(":status", "200"),
          RawHeader("content-type", "application/octet-stream")
        ))

        val response = user.expectResponse()
        response.entity shouldBe a[Chunked]

        network.sendDATA(streamId, endStream = false, ByteString("asdf"))
        network.sendHEADERS(streamId, endStream = true, Seq(RawHeader("grpc-status", "0")))

        val chunks = response.entity.asInstanceOf[Chunked].chunks.runWith(Sink.seq).futureValue
        chunks(0) should be(Chunk("asdf"))
        chunks(1) should be(LastChunk(extension = "", List(RawHeader("grpc-status", "0"))))
      }

    }
  }

  protected /* To make ByteFlag warnings go away */ abstract class TestSetupWithoutHandshake {
    implicit def ec = system.dispatcher

    private lazy val responseIn = TestSubscriber.probe[HttpResponse]()
    private lazy val requestOut = TestPublisher.probe[HttpRequest]()

    def netFlow: Flow[ByteString, ByteString, NotUsed]

    // hook to modify client, for example to add attributes
    def modifyClient(client: BidiFlow[HttpRequest, ByteString, ByteString, HttpResponse, NotUsed]) = client

    // hook to modify client settings
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

    lazy val user = new UserSide(requestOut, responseIn)
  }

  class UserSide(val requestOut: TestPublisher.Probe[HttpRequest], val responseIn: TestSubscriber.Probe[HttpResponse]) {
    def expectResponse(): HttpResponse = responseIn.requestNext().removeAttribute(Http2.streamId)
    def expectResponseRaw(): HttpResponse = responseIn.requestNext() // TODO, make it so that internal headers are not listed in `headers` etc?
    def emitRequest(streamId: Int, request: HttpRequest): Unit =
      requestOut.sendNext(request.addAttribute(Http2.streamId, streamId))
  }

  /** Basic TestSetup that has already passed the exchange of the connection preface */
  abstract class TestSetup(initialServerSettings: Setting*) extends TestSetupWithoutHandshake with NetProbes {
    network.toNet.expectBytes(Http2Protocol.ClientConnectionPreface)
    network.expectSETTINGS()

    network.sendFrame(SettingsFrame(immutable.Seq.empty ++ initialServerSettings))
    network.expectSettingsAck()
  }

  /** Provides the net flow as `toNet` and `fromNet` probes for manual stream interaction */
  trait NetProbes extends TestSetupWithoutHandshake {
    private lazy val framesOut: Http2FrameProbe = Http2FrameProbe()
    private lazy val toNet: ByteStringSinkProbe = framesOut.plainDataProbe
    private lazy val fromNet: TestPublisher.Probe[ByteString] = TestPublisher.probe[ByteString]()

    override def netFlow: Flow[ByteString, ByteString, NotUsed] =
      Flow.fromSinkAndSource(toNet.sink, Source.fromPublisher(fromNet))

    def expectGracefulCompletion(): Unit = {
      user.responseIn.expectComplete()
      toNet.expectComplete()
    }

    lazy val network = new NetworkSide(fromNet, toNet, framesOut)
  }
  class NetworkSide(val fromNet: TestPublisher.Probe[ByteString], val toNet: ByteStringSinkProbe, val framesOut: Http2FrameProbe) extends Http2FrameProbeDelegator with Http2FrameHpackSupport with Http2FrameSending {
    override def frameProbeDelegate: Http2FrameProbe = framesOut

    def sendBytes(bytes: ByteString): Unit = fromNet.sendNext(bytes)
  }
}
