/*
 * Copyright (C) 2018-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import java.net.InetSocketAddress
import akka.NotUsed
import akka.event.Logging
import akka.http.impl.engine.http2.FrameEvent._
import akka.http.impl.engine.http2.Http2Protocol.ErrorCode
import akka.http.impl.engine.http2.Http2Protocol.Flags
import akka.http.impl.engine.http2.Http2Protocol.FrameType
import akka.http.impl.engine.http2.Http2Protocol.SettingIdentifier
import akka.http.impl.engine.server.{ HttpAttributes, ServerTerminator }
import akka.http.impl.engine.ws.ByteStringSinkProbe
import akka.http.impl.util.AkkaSpecWithMaterializer
import akka.http.impl.util.LogByteStringTools
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding.Get
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.CacheDirectives
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.settings.ServerSettings
import akka.stream.Attributes
import akka.stream.Attributes.LogLevels
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{ BidiFlow, Flow, Keep, Sink, Source, SourceQueueWithComplete }
import akka.stream.testkit.TestPublisher.{ ManualProbe, Probe }
import akka.stream.testkit.scaladsl.StreamTestKit
import akka.stream.testkit.TestPublisher
import akka.stream.testkit.TestSubscriber
import akka.testkit._
import akka.util.ByteString

import scala.annotation.nowarn
import javax.net.ssl.SSLContext
import org.scalatest.concurrent.Eventually
import org.scalatest.concurrent.PatienceConfiguration.Timeout

import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise

/**
 * This tests the http2 server protocol logic.
 *
 * Tests typically:
 * * provide incoming data frames
 * * if applicable: validate the constructed incoming application-level request
 * * if applicable: provide application-level response
 * * validate the produced response frames
 */
class Http2ServerSpec extends AkkaSpecWithMaterializer("""
    akka.http.server.remote-address-header = on
    akka.http.server.http2.log-frames = on
  """)
  with Eventually {
  override def failOnSevereMessages: Boolean = true

  "The Http/2 server implementation" should {
    "support simple round-trips" should {
      abstract class SimpleRequestResponseRoundtripSetup extends TestSetup with RequestResponseProbes {
        def requestResponseRoundtrip(
          streamId:                    Int,
          requestHeaderBlock:          ByteString,
          expectedRequest:             HttpRequest,
          response:                    HttpResponse,
          expectedResponseHeaderBlock: ByteString
        ): Unit = {
          network.sendHEADERS(streamId, endStream = true, endHeaders = true, requestHeaderBlock)
          user.expectRequest() shouldBe expectedRequest

          user.emitResponse(streamId, response)
          val headerPayload = network.expectHeaderBlock(streamId)
          headerPayload shouldBe expectedResponseHeaderBlock
        }
      }

      "GET request in one HEADERS frame" inAssertAllStagesStopped new SimpleRequestResponseRoundtripSetup {
        requestResponseRoundtrip(
          streamId = 1,
          requestHeaderBlock = HPackSpecExamples.C41FirstRequestWithHuffman,
          expectedRequest = HttpRequest(HttpMethods.GET, "http://www.example.com/", protocol = HttpProtocols.`HTTP/2.0`),
          response = HPackSpecExamples.FirstResponse,
          expectedResponseHeaderBlock = HPackSpecExamples.C61FirstResponseWithHuffman
        )
      }
      "GOAWAY when invalid headers frame" inAssertAllStagesStopped new TestSetup with RequestResponseProbes {
        override def handlerFlow: Flow[HttpRequest, HttpResponse, NotUsed] =
          Flow[HttpRequest].map { req =>
            HttpResponse(entity = req.entity).addAttribute(Http2.streamId, req.attribute(Http2.streamId).get)
          }

        val headerBlock = hex"00 00 01 01 05 00 00 00 01 40"
        network.sendHEADERS(1, endStream = false, endHeaders = true, headerBlockFragment = headerBlock)

        val (_, errorCode) = network.expectGOAWAY(0) // since we have not processed any stream
        errorCode should ===(ErrorCode.COMPRESSION_ERROR)
      }
      "GOAWAY when second request on different stream has invalid headers frame" inAssertAllStagesStopped new SimpleRequestResponseRoundtripSetup {
        requestResponseRoundtrip(
          streamId = 1,
          requestHeaderBlock = HPackSpecExamples.C41FirstRequestWithHuffman,
          expectedRequest = HttpRequest(HttpMethods.GET, "http://www.example.com/", protocol = HttpProtocols.`HTTP/2.0`),
          response = HPackSpecExamples.FirstResponse,
          expectedResponseHeaderBlock = HPackSpecExamples.C61FirstResponseWithHuffman
        )

        // example from: https://github.com/summerwind/h2spec/blob/master/4_3.go#L18
        val incorrectHeaderBlock = hex"00 00 01 01 05 00 00 00 01 40"
        network.sendHEADERS(3, endStream = false, endHeaders = true, headerBlockFragment = incorrectHeaderBlock)

        val (_, errorCode) = network.expectGOAWAY(1) // since we have successfully started processing stream `1`
        errorCode should ===(ErrorCode.COMPRESSION_ERROR)
      }
      "Three consecutive GET requests" inAssertAllStagesStopped new SimpleRequestResponseRoundtripSetup {
        import CacheDirectives._
        import headers.`Cache-Control`
        requestResponseRoundtrip(
          streamId = 1,
          requestHeaderBlock = HPackSpecExamples.C41FirstRequestWithHuffman,
          expectedRequest = HttpRequest(HttpMethods.GET, "http://www.example.com/", protocol = HttpProtocols.`HTTP/2.0`),
          response = HPackSpecExamples.FirstResponse,
          expectedResponseHeaderBlock = HPackSpecExamples.C61FirstResponseWithHuffman
        )
        requestResponseRoundtrip(
          streamId = 3,
          requestHeaderBlock = HPackSpecExamples.C42SecondRequestWithHuffman,
          expectedRequest = HttpRequest(
            method = HttpMethods.GET,
            uri = "http://www.example.com/",
            headers = Vector(`Cache-Control`(`no-cache`)),
            protocol = HttpProtocols.`HTTP/2.0`),
          response = HPackSpecExamples.SecondResponse,
          // our hpack compressor chooses the non-huffman form probably because it seems to have same length
          expectedResponseHeaderBlock = HPackSpecExamples.C52SecondResponseWithoutHuffman
        )
        requestResponseRoundtrip(
          streamId = 5,
          requestHeaderBlock = HPackSpecExamples.C43ThirdRequestWithHuffman,
          expectedRequest = HttpRequest(
            method = HttpMethods.GET,
            uri = "https://www.example.com/index.html",
            headers = RawHeader("custom-key", "custom-value") :: Nil,
            protocol = HttpProtocols.`HTTP/2.0`),
          response = HPackSpecExamples.ThirdResponse,
          expectedResponseHeaderBlock = HPackSpecExamples.C63ThirdResponseWithHuffman
        )
      }
      "GET request in one HEADERS and one CONTINUATION frame" inAssertAllStagesStopped new TestSetup with RequestResponseProbes {
        val headerBlock = HPackSpecExamples.C41FirstRequestWithHuffman
        val fragment1 = headerBlock.take(8) // must be grouped by octets
        val fragment2 = headerBlock.drop(8)

        network.sendHEADERS(1, endStream = true, endHeaders = false, fragment1)
        user.requestIn.ensureSubscription()
        user.requestIn.expectNoMessage(100.millis)
        network.sendCONTINUATION(1, endHeaders = true, fragment2)

        val request = user.expectRequestRaw()

        request.method shouldBe HttpMethods.GET
        request.uri shouldBe Uri("http://www.example.com/")

        val streamId = request.attribute(Http2.streamId).getOrElse(Http2Compliance.missingHttpIdHeaderException)
        user.emitResponse(streamId, HPackSpecExamples.FirstResponse)
        val headerPayload = network.expectHeaderBlock(1)
        headerPayload shouldBe HPackSpecExamples.C61FirstResponseWithHuffman
      }
      "GET request in one HEADERS and two CONTINUATION frames" inAssertAllStagesStopped new TestSetup with RequestResponseProbes {
        val headerBlock = HPackSpecExamples.C41FirstRequestWithHuffman
        val fragment1 = headerBlock.take(8) // must be grouped by octets
        val fragment2 = headerBlock.drop(8).take(8)
        val fragment3 = headerBlock.drop(16)

        network.sendHEADERS(1, endStream = true, endHeaders = false, fragment1)
        user.requestIn.ensureSubscription()
        user.requestIn.expectNoMessage(100.millis)
        network.sendCONTINUATION(1, endHeaders = false, fragment2)
        network.sendCONTINUATION(1, endHeaders = true, fragment3)

        val request = user.expectRequestRaw()

        request.method shouldBe HttpMethods.GET
        request.uri shouldBe Uri("http://www.example.com/")

        val streamId = request.attribute(Http2.streamId).getOrElse(Http2Compliance.missingHttpIdHeaderException)
        user.emitResponse(streamId, HPackSpecExamples.FirstResponse)
        val headerPayload = network.expectHeaderBlock(1)
        headerPayload shouldBe HPackSpecExamples.C61FirstResponseWithHuffman
      }

      "fail if Http2StreamIdHeader missing" in pending
      "automatically add `Date` header" in pending

      "support custom methods" inAssertAllStagesStopped new TestSetup with RequestResponseProbes {
        lazy val customMethod = HttpMethod.custom("BANANA")
        override def settings = {
          val default = super.settings
          default.withParserSettings(default.parserSettings.withCustomMethods(customMethod))
        }

        val request = HttpRequest(
          method = customMethod,
          uri = "http://www.example.com/",
          headers = Vector(),
          protocol = HttpProtocols.`HTTP/2.0`)
        val streamId = 1
        val requestHeaderBlock = network.encodeRequestHeaders(request)
        network.sendHEADERS(streamId, endStream = true, endHeaders = true, requestHeaderBlock)
        user.expectRequest().method shouldBe customMethod
      }

      "parse headers to modeled headers" inAssertAllStagesStopped new TestSetup with RequestResponseProbes {
        import CacheDirectives._
        import headers._
        val expectedRequest = HttpRequest(
          method = HttpMethods.GET,
          uri = "http://www.example.com/",
          headers = Vector(`Cache-Control`(`no-cache`), `Cache-Control`(`max-age`(1000)), `Access-Control-Allow-Origin`.`*`),
          protocol = HttpProtocols.`HTTP/2.0`)
        val streamId = 1
        val requestHeaderBlock = network.encodeRequestHeaders(HttpRequest(
          method = HttpMethods.GET,
          uri = "http://www.example.com/",
          headers = Vector(
            RawHeader("cache-control", "no-cache"),
            RawHeader("cache-control", "max-age=1000"),
            RawHeader("access-control-allow-origin", "*")),
          protocol = HttpProtocols.`HTTP/2.0`))
        network.sendHEADERS(streamId, endStream = true, endHeaders = true, requestHeaderBlock)
        user.expectRequest().headers shouldBe expectedRequest.headers
      }

      "allow trailing headers on strict responses" inAssertAllStagesStopped new TestSetup with RequestResponseProbes {
        val streamId = 1
        network.sendHEADERS(streamId, endStream = true, network.headersForRequest(Get("/")))
        user.expectRequest()
        val response =
          HttpResponse(StatusCodes.OK, entity = HttpEntity.Strict(ContentTypes.`application/octet-stream`, ByteString("Hello")))
            .addAttribute(AttributeKeys.trailer, Trailer(RawHeader("Status", "grpc-status 10")))
        user.emitResponse(streamId, response)

        network.expectHeaderBlock(streamId, endStream = false)
        network.expectDATA(streamId, endStream = false, ByteString("Hello"))
        val trailingResponseHeaders = network.expectDecodedResponseHEADERSPairs(streamId)
        trailingResponseHeaders.size should be(1)
        trailingResponseHeaders.head should be(("Status", "grpc-status 10"))
      }
      "consider stream as closed after sending out strict response > WINDOW_SIZE" inAssertAllStagesStopped new TestSetup with RequestResponseProbes {
        override def settings: ServerSettings =
          // allow only single stream to be able to probe whether main stream is closed
          super.settings.mapHttp2Settings(_.withMaxConcurrentStreams(1))

        network.sendSETTING(SettingIdentifier.SETTINGS_INITIAL_WINDOW_SIZE, 1000)
        network.expectSettingsAck()

        val streamId = 1
        network.sendHEADERS(streamId, endStream = true, network.headersForRequest(Get("/")))
        user.expectRequest()
        val response =
          HttpResponse(StatusCodes.OK, entity = HttpEntity.Strict(
            ContentTypes.`application/octet-stream`,
            ByteString("a" * 2000))) // > default INITIAL_WINDOW_SIZE
            .addAttribute(AttributeKeys.trailer, Trailer(RawHeader("Status", "grpc-status 10")))

        // single configured stream is ongoing, so extra streams will be refused
        network.sendRequest(3, HttpRequest())
        network.expectRST_STREAM(3, ErrorCode.REFUSED_STREAM)

        user.emitResponse(streamId, response)

        network.expectHeaderBlock(streamId, endStream = false)
        network.expectDATA(streamId, endStream = false, ByteString("a" * 1000))
        network.toNet.request(5)
        network.sendWINDOW_UPDATE(streamId, 1000)
        network.expectDATA(streamId, endStream = false, ByteString("a" * 1000))
        val trailingResponseHeaders = network.expectDecodedResponseHEADERSPairs(streamId)
        trailingResponseHeaders.size should be(1)
        trailingResponseHeaders.head should be(("Status", "grpc-status 10"))

        network.sendRequest(5, HttpRequest())
        user.expectRequest()
      }

      "acknowledge change to SETTINGS_HEADER_TABLE_SIZE in next HEADER frame" inAssertAllStagesStopped new TestSetup with RequestResponseProbes {
        network.sendSETTING(SettingIdentifier.SETTINGS_HEADER_TABLE_SIZE, 8192)
        network.expectSettingsAck()

        val headerBlock = HPackSpecExamples.C41FirstRequestWithHuffman

        network.sendHEADERS(1, endStream = true, endHeaders = true, headerBlock)
        user.requestIn.ensureSubscription()
        user.requestIn.expectNoMessage(100.millis)

        val request = user.expectRequestRaw()

        request.method shouldBe HttpMethods.GET
        request.uri shouldBe Uri("http://www.example.com/")

        val streamId = request.attribute(Http2.streamId).getOrElse(Http2Compliance.missingHttpIdHeaderException)
        user.emitResponse(streamId, HPackSpecExamples.FirstResponse)
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
        headerPayload shouldBe dynamicTableUpdateTo8192 ++ HPackSpecExamples.C61FirstResponseWithHuffman
      }
    }

    def requestTests(minCollectStrictEntityBytes: Int) = {
      abstract class RequestEntityTestSetup extends TestSetup with RequestResponseProbes {
        override def settings: ServerSettings = super.settings.mapHttp2Settings(_.withMinCollectStrictEntitySize(minCollectStrictEntityBytes))

        val TheStreamId = 1
        protected def sendRequest(): Unit

        sendRequest()

        lazy val (receivedRequest, entityDataIn) = {
          val receivedRequest = user.expectRequest()
          val entityDataIn = ByteStringSinkProbe()
          receivedRequest.entity.dataBytes.runWith(entityDataIn.sink)
          entityDataIn.ensureSubscription()
          (receivedRequest, entityDataIn)
        }
      }

      abstract class WaitingForRequest(request: HttpRequest) extends RequestEntityTestSetup {
        protected def sendRequest(): Unit = network.sendRequest(TheStreamId, request)
      }
      abstract class WaitingForRequestData extends RequestEntityTestSetup {
        lazy val request = HttpRequest(method = HttpMethods.POST, uri = "https://example.com/upload", protocol = HttpProtocols.`HTTP/2.0`)

        protected def sendRequest(): Unit =
          network.sendRequestHEADERS(TheStreamId, request, endStream = false)
      }
      s"support stream for request entity data (min-collect-strict-entity-bytes = $minCollectStrictEntityBytes)" should {
        "send data frames to entity stream" inAssertAllStagesStopped new WaitingForRequestData {
          val data1 = ByteString("abcdef")
          network.sendDATA(TheStreamId, endStream = false, data1)
          entityDataIn.expectBytes(data1)

          val data2 = ByteString("zyxwvu")
          network.sendDATA(TheStreamId, endStream = false, data2)
          entityDataIn.expectBytes(data2)

          val data3 = ByteString("mnopq")
          network.sendDATA(TheStreamId, endStream = true, data3)
          entityDataIn.expectBytes(data3)
          entityDataIn.expectComplete()
        }
        "handle content-length and content-type of incoming request" inAssertAllStagesStopped new WaitingForRequest(
          HttpRequest(
            method = HttpMethods.POST,
            uri = "https://example.com/upload",
            entity = HttpEntity(ContentTypes.`application/json`, 1337, Source.repeat("x").take(1337).map(ByteString(_))),
            protocol = HttpProtocols.`HTTP/2.0`)) {

          receivedRequest.entity.contentType should ===(ContentTypes.`application/json`)
          // FIXME: contentLength is not reported in all cases with HTTP/2
          // see https://github.com/akka/akka-http/issues/3843
          // receivedRequest.entity.isIndefiniteLength should ===(false)
          // receivedRequest.entity.contentLengthOption should ===(Some(1337L))
          entityDataIn.expectBytes(ByteString("x" * 1337))
          entityDataIn.expectComplete()
        }
        "fail entity stream if peer sends RST_STREAM frame" inAssertAllStagesStopped new WaitingForRequestData {
          val data1 = ByteString("abcdef")
          network.sendDATA(TheStreamId, endStream = false, data1)
          entityDataIn.expectBytes(data1)

          network.sendRST_STREAM(TheStreamId, ErrorCode.INTERNAL_ERROR)
          val error = entityDataIn.expectError()
          error.getMessage shouldBe "Stream with ID [1] was closed by peer with code INTERNAL_ERROR(0x02)"
        }
        if (minCollectStrictEntityBytes != 0)
          "not dispatch request at all if RST is received before any data" inAssertAllStagesStopped new WaitingForRequestData {
            network.sendRST_STREAM(TheStreamId, ErrorCode.INTERNAL_ERROR)
            user.requestIn.ensureSubscription()
            user.requestIn.expectNoMessage(100.millis)

            // error is not surfaced anywhere
          }

        // Reproducing https://github.com/akka/akka-http/issues/2957
        "close the stream when we receive a RST after we have half-closed ourselves as well" inAssertAllStagesStopped new WaitingForRequestData {
          // Client sends the request, but doesn't close the stream yet. This is a bit weird, but it's what grpcurl does ;)
          network.sendDATA(streamId = TheStreamId, endStream = false, ByteString(0, 0, 0, 0, 0x10, 0x22, 0x0e) ++ ByteString.fromString("GreeterService"))

          // We emit a 404 response, half-closing the stream.
          user.emitResponse(streamId = TheStreamId, HttpResponse(StatusCodes.NotFound))

          // The client closes the stream with a protocol error. This is somewhat questionable but it's what grpc-go does
          network.sendRST_STREAM(streamId = TheStreamId, ErrorCode.PROTOCOL_ERROR)
          entityDataIn.expectError()
          // Wait to give the warning (that we hope not to see) time to pop up.
          Thread.sleep(100)
        }
        if (minCollectStrictEntityBytes == 0)
          "not fail the whole connection when one stream is RST twice" inAssertAllStagesStopped new WaitingForRequestData {
            network.sendRST_STREAM(TheStreamId, ErrorCode.STREAM_CLOSED)
            val error = entityDataIn.expectError()
            error.getMessage shouldBe "Stream with ID [1] was closed by peer with code STREAM_CLOSED(0x05)"
            network.expectNoBytes(100.millis)

            // https://http2.github.io/http2-spec/#StreamStates
            // Endpoints MUST ignore WINDOW_UPDATE or RST_STREAM frames received in this state,
            network.sendRST_STREAM(TheStreamId, ErrorCode.STREAM_CLOSED)
            network.expectNoBytes(100.millis)
          }

        "not fail the whole connection when data frames are received after stream was cancelled" inAssertAllStagesStopped new WaitingForRequestData {
          // send some initial data
          val data1 = ByteString("abcdef")
          network.sendDATA(TheStreamId, endStream = false, data1)
          entityDataIn.expectBytes(data1)
          network.pollForWindowUpdates(10.millis)

          // cancel stream
          entityDataIn.cancel()
          network.expectRST_STREAM(TheStreamId)

          network.sendDATA(TheStreamId, endStream = false, ByteString("test"))
          // should just be ignored, especially no GOAWAY frame should be sent in response
          network.expectNoBytes(100.millis)
        }
        "send RST_STREAM if entity stream is canceled" inAssertAllStagesStopped new WaitingForRequestData {
          val data1 = ByteString("abcdef")
          network.sendDATA(TheStreamId, endStream = false, data1)
          entityDataIn.expectBytes(data1)

          network.pollForWindowUpdates(10.millis)

          entityDataIn.cancel()
          network.expectRST_STREAM(TheStreamId, ErrorCode.CANCEL)
        }
        "send out WINDOW_UPDATE frames when request data is read so that the stream doesn't stall" inAssertAllStagesStopped new WaitingForRequestData {
          (1 to 10).foreach { _ =>
            val bytesSent = network.sendWindowFullOfData(TheStreamId)
            bytesSent should be > 0
            entityDataIn.expectBytes(bytesSent)
            network.pollForWindowUpdates(10.millis)
            network.remainingWindowForIncomingData(TheStreamId) should be > 0
          }
        }
        "backpressure until request entity stream is read (don't send out unlimited WINDOW_UPDATE before)" inAssertAllStagesStopped new WaitingForRequestData {
          var totallySentBytes = 0
          // send data until we don't receive any window updates from the implementation any more
          eventually(Timeout(1.second.dilated)) {
            totallySentBytes += network.sendWindowFullOfData(TheStreamId)
            // the implementation may choose to send a few window update until internal buffers are filled
            network.pollForWindowUpdates(10.millis)
            network.remainingWindowForIncomingData(TheStreamId) shouldBe 0
          }

          // now drain entity source
          entityDataIn.expectBytes(totallySentBytes)

          eventually(Timeout(1.second.dilated)) {
            network.pollForWindowUpdates(10.millis)
            network.remainingWindowForIncomingData(TheStreamId) should be > 0
          }
        }
        "send data frames to entity stream and ignore trailing headers" inAssertAllStagesStopped new WaitingForRequestData {
          val data1 = ByteString("abcdef")
          network.sendDATA(TheStreamId, endStream = false, data1)
          entityDataIn.expectBytes(data1)

          network.sendHEADERS(TheStreamId, endStream = true, Seq(headers.`Cache-Control`(CacheDirectives.`no-cache`)))
          entityDataIn.expectComplete()
        }

        "fail if more data is received than connection window allows" inAssertAllStagesStopped new WaitingForRequestData {
          network.sendFrame(DataFrame(TheStreamId, endStream = false, ByteString("0" * 100000)))
          val (_, errorCode) = network.expectGOAWAY()
          errorCode shouldEqual ErrorCode.FLOW_CONTROL_ERROR
        }
        "fail if more data is received than stream-level window allows" inAssertAllStagesStopped new WaitingForRequestData {
          // trigger a connection-level WINDOW_UPDATE
          network.sendDATA(TheStreamId, endStream = false, ByteString("0000"))
          entityDataIn.expectUtf8EncodedString("0000")
          network.pollForWindowUpdates(500.millis) // window resize/update triggered

          network.sendFrame(DataFrame(TheStreamId, endStream = false, ByteString("0" * 512001))) // more than default `incoming-stream-level-buffer-size = 512kB`
          network.expectRST_STREAM(TheStreamId, ErrorCode.FLOW_CONTROL_ERROR)
        }
        "fail stream if request entity is not fully pulled when connection dies" inAssertAllStagesStopped new WaitingForRequestData {
          network.sendDATA(TheStreamId, endStream = false, ByteString("0000"))
          entityDataIn.expectUtf8EncodedString("0000")
          network.pollForWindowUpdates(500.millis)

          val bigData = ByteString("1" * 100000) // more than configured request-entity-chunk-size, so that something is left in buffer
          network.sendDATA(TheStreamId, endStream = false, bigData)
          network.sendDATA(TheStreamId, endStream = true, ByteString.empty)

          // DATA is left in IncomingStreamBuffer because we never pulled
          network.toNet.cancel()
          network.fromNet.sendError(new RuntimeException("connection crashed"))

          // we have received all data for the stream, but the substream cannot push it any more because the owning stage is gone
          entityDataIn.expectError().getMessage shouldBe "The HTTP/2 connection was shut down while the request was still ongoing"
        }
        "fail if DATA frame arrives after incoming stream has already been closed (before response was sent)" inAssertAllStagesStopped new WaitingForRequestData {
          network.sendDATA(TheStreamId, endStream = false, ByteString("0000"))
          entityDataIn.expectUtf8EncodedString("0000")
          network.pollForWindowUpdates(500.millis)

          val bigData = ByteString("1" * 100000) // more than configured request-entity-chunk-size, so that something is left in buffer
          network.sendDATA(TheStreamId, endStream = false, bigData)
          network.sendDATA(TheStreamId, endStream = true, ByteString.empty) // close stream

          // now send more DATA: checks that we have moved into a state where DATA is not expected any more
          network.sendDATA(TheStreamId, endStream = false, ByteString("more data"))
          val (_, errorCode) = network.expectGOAWAY()
          errorCode shouldEqual ErrorCode.PROTOCOL_ERROR

          // we have received all data for the stream, but the substream cannot push it any more because the owning stage is gone
          entityDataIn.expectError().getMessage shouldBe "The HTTP/2 connection was shut down while the request was still ongoing"
        }
        "fail entity stream if advertised content-length doesn't match" in pending

        "collect Strict entity for single DATA frame" inAssertAllStagesStopped new TestSetup with RequestResponseProbes {
          override def settings: ServerSettings = super.settings.mapHttp2Settings(_.withMinCollectStrictEntitySize(1))
          network.sendRequestHEADERS(1, HttpRequest(HttpMethods.POST, entity = HttpEntity("abcde")), endStream = false)
          user.requestIn.ensureSubscription()
          user.requestIn.expectNoMessage(100.millis) // don't expect request yet
          network.sendDATA(1, endStream = true, ByteString("abcde")) // send final frame
          val receivedRequest = user.expectRequest()

          receivedRequest.entity shouldBe Symbol("strict")
          receivedRequest.entity.asInstanceOf[HttpEntity.Strict].data.utf8String shouldBe "abcde"
        }
        "collect Strict entity for multiple DATA frames if min-collect-strict-entity-size is set accordingly" inAssertAllStagesStopped new TestSetup with RequestResponseProbes {
          override def settings: ServerSettings = super.settings.mapHttp2Settings(_.withMinCollectStrictEntitySize(10))
          network.sendRequestHEADERS(1, HttpRequest(HttpMethods.POST, entity = HttpEntity("abcde")), endStream = false)
          user.requestIn.ensureSubscription()
          user.requestIn.expectNoMessage(100.millis) // don't expect request yet
          network.sendDATA(1, endStream = false, ByteString("abcde")) // send final frame
          user.requestIn.expectNoMessage(100.millis) // don't expect request yet
          network.sendDATA(1, endStream = true, ByteString("fghij")) // send fi
          val receivedRequest = user.expectRequest()

          receivedRequest.entity shouldBe Symbol("strict")
          receivedRequest.entity.asInstanceOf[HttpEntity.Strict].data.utf8String shouldBe "abcdefghij"
        }
        "create streamed entity for multiple DATA frames if min-collect-strict-entity-size is too low" inAssertAllStagesStopped new TestSetup with RequestResponseProbes {
          override def settings: ServerSettings = super.settings.mapHttp2Settings(_.withMinCollectStrictEntitySize(10))
          network.sendRequestHEADERS(1, HttpRequest(HttpMethods.POST, entity = HttpEntity("abcde")), endStream = false)
          user.requestIn.ensureSubscription()
          user.requestIn.expectNoMessage(100.millis) // don't expect request yet
          network.sendDATA(1, endStream = false, ByteString("abcde")) // send final frame
          user.requestIn.expectNoMessage(100.millis) // don't expect request yet
          network.sendDATA(1, endStream = false, ByteString("fghij")) // send fi
          val receivedRequest = user.expectRequest()

          receivedRequest.entity shouldNot be(Symbol("strict"))
          val entityDataIn = ByteStringSinkProbe()
          receivedRequest.entity.dataBytes.runWith(entityDataIn.sink)
          entityDataIn.ensureSubscription()
          entityDataIn.expectUtf8EncodedString("abcdefghij")

          network.sendDATA(1, endStream = false, ByteString("klmnop")) // send fi
          entityDataIn.expectUtf8EncodedString("klmnop")
          network.sendDATA(1, endStream = true, ByteString.empty) // send fi
          entityDataIn.expectComplete()
        }
        def sendOutConnectionLevelWindowUpdate(singleDataFrame: Boolean) =
          s"eventually send out WINDOW_UPDATE for dispatched data (singleDataFrame = $singleDataFrame)" inAssertAllStagesStopped new WaitingForRequestData {
            override def settings: ServerSettings =
              super.settings.mapHttp2Settings(_.withIncomingConnectionLevelBufferSize(Http2Protocol.InitialWindowSize)) // set to initial size

            val data = ByteString("x" * Http2Protocol.InitialWindowSize) // exactly fills window
            network.sendDATA(TheStreamId, endStream = singleDataFrame, data)
            if (!singleDataFrame) network.sendDATA(TheStreamId, endStream = true, ByteString.empty)

            entityDataIn.expectBytes(data)
            network.remainingWindowForIncomingDataOnConnection shouldEqual 0
            network.expectWindowUpdate()
            network.remainingWindowForIncomingDataOnConnection shouldEqual Http2Protocol.InitialWindowSize
          }
        sendOutConnectionLevelWindowUpdate(singleDataFrame = true) // to examine strict entity handling when minCollectStrictEntityBytes != 0
        sendOutConnectionLevelWindowUpdate(singleDataFrame = false) // to examine what happens when entity is collected but then converted to stream

        /* These cases are different when minCollectStrictEntityBytes = 1 because either a strict or a streamed request entity are then created  */
        def handleEarlyWindowUpdateCorrectly(endStreamSeen: Boolean) =
          s"handle WINDOW_UPDATE correctly when received before started sending out response and while receiving request data (seen endStream = $endStreamSeen)" inAssertAllStagesStopped new WaitingForRequestData {
            val bytesToSend = 70000 // > Http2Protocol.InitialWindowSize
            val missingWindow = bytesToSend - Http2Protocol.InitialWindowSize
            require(missingWindow >= 0)
            // add missing window space immediately to both connection- and stream-level window
            network.sendWINDOW_UPDATE(0, missingWindow)
            network.sendWINDOW_UPDATE(TheStreamId, missingWindow)

            // this will create a streaming entity even when `minCollectStrictEntityBytes = 1` because endStream = false
            val data1 = ByteString("abcdef")
            network.sendDATA(TheStreamId, endStream = endStreamSeen, data1)
            entityDataIn.expectBytes(data1)
            if (endStreamSeen) entityDataIn.expectComplete()
            network.pollForWindowUpdates(100.millis)

            val entityDataOut = TestPublisher.probe[ByteString]()

            val response = HttpResponse(entity = HttpEntity(ContentTypes.`application/octet-stream`, Source.fromPublisher(entityDataOut)))
            user.emitResponse(TheStreamId, response)
            network.expectDecodedHEADERS(streamId = TheStreamId, endStream = false) shouldBe response.withEntity(HttpEntity.Empty.withContentType(ContentTypes.`application/octet-stream`))
            entityDataOut.sendNext(bytes(bytesToSend, 0x23))

            network.expectDATA(TheStreamId, false, bytesToSend)

            entityDataOut.sendComplete()
            network.expectDATA(TheStreamId, true, 0)
          }

        handleEarlyWindowUpdateCorrectly(endStreamSeen = false)
        handleEarlyWindowUpdateCorrectly(endStreamSeen = true)
      }
    }

    requestTests(minCollectStrictEntityBytes = 0)
    requestTests(minCollectStrictEntityBytes = 1)

    "support streaming for sending response entity data" should {
      abstract class WaitingForResponseSetup extends TestSetup with RequestResponseProbes {
        val TheStreamId = 1
        val theRequest = HttpRequest(protocol = HttpProtocols.`HTTP/2.0`)
        network.sendRequest(TheStreamId, theRequest)
        user.expectRequest() shouldBe theRequest
      }
      abstract class WaitingForResponseDataSetup extends WaitingForResponseSetup {
        val entityDataOut = TestPublisher.probe[ByteString]()

        val response = HttpResponse(entity = HttpEntity(ContentTypes.`application/octet-stream`, Source.fromPublisher(entityDataOut)))
        user.emitResponse(TheStreamId, response)
        network.expectDecodedHEADERS(streamId = TheStreamId, endStream = false) shouldBe response.withEntity(HttpEntity.Empty.withContentType(ContentTypes.`application/octet-stream`))
      }

      "encode Content-Length and Content-Type headers" inAssertAllStagesStopped new WaitingForResponseSetup {
        val response = HttpResponse(entity = HttpEntity(ContentTypes.`application/octet-stream`, ByteString("abcde")))
        user.emitResponse(TheStreamId, response)
        val pairs = network.expectDecodedResponseHEADERSPairs(streamId = TheStreamId, endStream = false).toMap
        pairs should contain(":status" -> "200")
        pairs should contain("content-length" -> "5")
        pairs should contain("content-type" -> "application/octet-stream")
      }

      "send entity data as data frames" inAssertAllStagesStopped new WaitingForResponseDataSetup {
        val data1 = ByteString("abcd")
        entityDataOut.sendNext(data1)
        network.expectDATA(TheStreamId, endStream = false, data1)

        val data2 = ByteString("efghij")
        entityDataOut.sendNext(data2)
        network.expectDATA(TheStreamId, endStream = false, data2)

        entityDataOut.sendComplete()
        network.expectDATA(TheStreamId, endStream = true, ByteString.empty)
      }
      "keep sending entity data when WINDOW_UPDATE is received intermediately" inAssertAllStagesStopped new WaitingForResponseDataSetup {
        val data1 = ByteString("abcd")
        entityDataOut.sendNext(data1)

        network.sendWINDOW_UPDATE(TheStreamId, 100)

        val data2 = ByteString("efghij")
        entityDataOut.sendNext(data2)

        val (false, data) = network.expectDATAFrame(TheStreamId)
        data shouldEqual data1 ++ data2

        // now don't fail if there's demand on the line
        network.plainDataProbe.request(1)
        network.expectNoBytes(100.millis)

        entityDataOut.sendComplete()
        network.expectDATA(TheStreamId, endStream = true, ByteString.empty)
      }
      "keep sending entity data when data is chunked into small bits" inAssertAllStagesStopped new WaitingForResponseDataSetup {
        val data1 = ByteString("a")
        val numChunks = 10000 // on my machine it crashed at ~1700
        (1 to numChunks).foreach(_ => entityDataOut.sendNext(data1))

        val (false, data) = network.expectDATAFrame(TheStreamId)
        data shouldEqual ByteString("a" * numChunks)

        // now don't fail if there's demand on the line
        network.plainDataProbe.request(1)
        network.expectNoBytes(100.millis)

        entityDataOut.sendComplete()
        network.expectDATA(TheStreamId, endStream = true, ByteString.empty)
      }

      "parse priority frames" inAssertAllStagesStopped new WaitingForResponseDataSetup {
        network.sendPRIORITY(TheStreamId, true, 0, 5)
        entityDataOut.sendComplete()
        network.expectDATA(TheStreamId, endStream = true, ByteString.empty)
      }

      "cancel entity data source when peer sends RST_STREAM" inAssertAllStagesStopped new WaitingForResponseDataSetup {
        val data1 = ByteString("abcd")
        entityDataOut.sendNext(data1)
        network.expectDATA(TheStreamId, endStream = false, data1)

        network.sendRST_STREAM(TheStreamId, ErrorCode.CANCEL)
        entityDataOut.expectCancellation()
      }

      "handle RST_STREAM while data is waiting in outgoing stream buffer" inAssertAllStagesStopped new WaitingForResponseDataSetup {
        val data1 = ByteString("abcd")
        entityDataOut.sendNext(data1)

        network.sendRST_STREAM(TheStreamId, ErrorCode.CANCEL)

        entityDataOut.expectCancellation()
        network.toNet.expectNoBytes(100.millis) // the whole stage failed with bug #2236
      }

      "handle WINDOW_UPDATE correctly when received before started sending out response" inAssertAllStagesStopped new WaitingForResponseSetup {
        val bytesToSend = 70000 // > Http2Protocol.InitialWindowSize
        val missingWindow = bytesToSend - Http2Protocol.InitialWindowSize
        require(missingWindow >= 0)
        // add missing window space immediately to both connection- and stream-level window
        network.sendWINDOW_UPDATE(0, missingWindow)
        network.sendWINDOW_UPDATE(TheStreamId, missingWindow)

        val entityDataOut = TestPublisher.probe[ByteString]()

        val response = HttpResponse(entity = HttpEntity(ContentTypes.`application/octet-stream`, Source.fromPublisher(entityDataOut)))
        user.emitResponse(TheStreamId, response)
        network.expectDecodedHEADERS(streamId = TheStreamId, endStream = false) shouldBe response.withEntity(HttpEntity.Empty.withContentType(ContentTypes.`application/octet-stream`))
        entityDataOut.sendNext(bytes(bytesToSend, 0x23))

        network.expectDATA(TheStreamId, false, bytesToSend)

        entityDataOut.sendComplete()
        network.expectDATA(TheStreamId, true, 0)
      }

      "distribute increases to SETTINGS_INITIAL_WINDOW_SIZE to streams correctly while sending out response" inAssertAllStagesStopped new WaitingForResponseDataSetup {
        // changes to SETTINGS_INITIAL_WINDOW_SIZE need to be distributed to active streams: https://httpwg.org/specs/rfc7540.html#InitialWindowSize
        val bytesToSend = 70000 // > Http2Protocol.InitialWindowSize
        val missingWindow = bytesToSend - Http2Protocol.InitialWindowSize
        require(missingWindow >= 0)
        // SETTINGS_INITIAL_WINDOW_SIZE only has ab effect on stream-level window, so we give the connection-level
        // window enough room immediately
        network.sendWINDOW_UPDATE(0, missingWindow)

        entityDataOut.sendNext(bytes(bytesToSend, 0x23))
        network.expectDATA(TheStreamId, false, Http2Protocol.InitialWindowSize)
        network.expectNoBytes(100.millis)

        // now increase SETTINGS_INITIAL_WINDOW_SIZE so that all data fits into WINDOW
        network.sendSETTING(Http2Protocol.SettingIdentifier.SETTINGS_INITIAL_WINDOW_SIZE, bytesToSend)
        network.updateFromServerWindows(TheStreamId, _ + missingWindow) // test probe doesn't automatically update window
        network.expectDATA(TheStreamId, false, missingWindow)
        network.expectSettingsAck() // FIXME: bug: we must send ACK before making use of the new setting, see https://github.com/akka/akka-http/issues/3553

        entityDataOut.sendComplete()
        network.expectDATA(TheStreamId, true, 0)
      }

      "handle RST_STREAM while waiting for a window update" inAssertAllStagesStopped new WaitingForResponseDataSetup {
        entityDataOut.sendNext(bytes(70000, 0x23)) // 70000 > Http2Protocol.InitialWindowSize
        network.sendWINDOW_UPDATE(TheStreamId, 10000) // enough window for the stream but not for the window

        network.expectDATA(TheStreamId, false, Http2Protocol.InitialWindowSize)

        // enough stream-level WINDOW, but too little connection-level WINDOW
        network.expectNoBytes(100.millis)

        // now the demuxer is in the WaitingForConnectionWindow state, cancel the connection
        network.sendRST_STREAM(TheStreamId, ErrorCode.CANCEL)

        entityDataOut.expectCancellation()
        network.expectNoBytes(100.millis)

        // now increase connection-level window again and see if everything still works
        network.sendWINDOW_UPDATE(0, 10000)
        network.expectNoBytes(100.millis) // don't expect anything, stream has been cancelled in the meantime
      }

      "cancel entity data source when peer sends RST_STREAM before entity is subscribed" inAssertAllStagesStopped new TestSetup with RequestResponseProbes {
        val theRequest = HttpRequest(protocol = HttpProtocols.`HTTP/2.0`)
        network.sendRequest(1, theRequest)
        user.expectRequest() shouldBe theRequest

        network.sendRST_STREAM(1, ErrorCode.CANCEL)

        val entityDataOut = TestPublisher.probe[ByteString]()
        val response = HttpResponse(entity = HttpEntity(ContentTypes.`application/octet-stream`, Source.fromPublisher(entityDataOut)))
        user.emitResponse(1, response)
        network.expectNoBytes(100.millis) // don't expect response on closed connection
        entityDataOut.expectCancellation()
      }

      "send RST_STREAM when entity data stream fails" inAssertAllStagesStopped new WaitingForResponseDataSetup {
        val data1 = ByteString("abcd")
        entityDataOut.sendNext(data1)
        network.expectDATA(TheStreamId, endStream = false, data1)

        class MyProblem extends RuntimeException

        EventFilter[MyProblem](pattern = "Substream 1 failed with .*", occurrences = 1).intercept {
          entityDataOut.sendError(new MyProblem)
          network.expectRST_STREAM(1, ErrorCode.INTERNAL_ERROR)
        }
      }
      "fail if advertised content-length doesn't match" in pending

      "send data frame with exactly the number of remaining connection-level window bytes even when chunk is bigger than that" inAssertAllStagesStopped new WaitingForResponseDataSetup {
        // otherwise, the stream may stall if the client doesn't send another window update (which it isn't required to do
        // unless a window falls to 0)

        entityDataOut.sendNext(bytes(70000, 0x23)) // 70000 > Http2Protocol.InitialWindowSize
        network.expectDATA(TheStreamId, false, Http2Protocol.InitialWindowSize)

        network.expectNoBytes(100.millis)

        network.sendWINDOW_UPDATE(TheStreamId, 10000) // > than the remaining bytes (70000 - InitialWindowSize)
        network.sendWINDOW_UPDATE(0, 10000)

        network.expectDATA(TheStreamId, false, 70000 - Http2Protocol.InitialWindowSize)
      }

      "backpressure response entity stream until WINDOW_UPDATE was received" inAssertAllStagesStopped new WaitingForResponseDataSetup {
        var totalSentBytes = 0
        var totalReceivedBytes = 0

        entityDataOut.ensureSubscription()

        def receiveData(maxBytes: Int = Int.MaxValue): Unit = {
          val (false, data) = network.expectDATAFrame(TheStreamId)
          totalReceivedBytes += data.size
        }

        def sendAWindow(): Unit = {
          val window = network.remainingFromServerWindowFor(TheStreamId)
          val dataToSend = window max 60000 // send at least 10 bytes
          entityDataOut.sendNext(bytes(dataToSend, 0x42))
          totalSentBytes += dataToSend

          if (window > 0) receiveData(window)
        }

        /**
         * Loop that checks for a while that publisherProbe has outstanding demand and runs body to fulfill it
         * Will fail if there's still demand after the timeout.
         */
        def fulfillDemandWithin(publisherProbe: TestPublisher.Probe[_], timeout: FiniteDuration)(body: => Unit): Unit = {
          // HACK to support `expectRequest` with a timeout
          def within[T](publisherProbe: TestPublisher.Probe[_], dur: FiniteDuration)(t: => T): T = {
            val field = classOf[ManualProbe[_]].getDeclaredField("probe")
            field.setAccessible(true)
            field.get(publisherProbe).asInstanceOf[TestProbe].within(dur)(t)
          }
          def expectRequest(timeout: FiniteDuration): Long =
            within(publisherProbe, timeout)(publisherProbe.expectRequest())

          eventually(Timeout(timeout)) {
            while (publisherProbe.pending > 0) body

            try expectRequest(10.millis.dilated)
            catch {
              case ex: Throwable => // ignore error here
            }
            publisherProbe.pending shouldBe 0 // fail here if there's still demand after the timeout
          }
        }

        fulfillDemandWithin(entityDataOut, 3.seconds.dilated)(sendAWindow())

        network.sendWINDOW_UPDATE(TheStreamId, totalSentBytes)
        network.sendWINDOW_UPDATE(0, totalSentBytes)

        while (totalReceivedBytes < totalSentBytes)
          receiveData()

        // we must get at least a bit of demand
        entityDataOut.sendNext(bytes(1000, 0x23))
      }
      "give control frames priority over pending data frames" inAssertAllStagesStopped new WaitingForResponseDataSetup {
        val responseDataChunk = bytes(1000, 0x42)

        // send data first but expect it to be queued because of missing demand
        entityDataOut.sendNext(responseDataChunk)

        // no receive a PING frame which should be answered with a PING(ack = true) frame
        val pingData = bytes(8, 0x23)
        network.sendFrame(PingFrame(ack = false, pingData))

        // now expect PING ack frame to "overtake" the data frame
        network.expectFrame(FrameType.PING, Flags.ACK, 0, pingData)
        network.expectDATA(TheStreamId, endStream = false, responseDataChunk)
      }
      "support trailing headers for chunked responses" inAssertAllStagesStopped new WaitingForResponseSetup {
        val response = HttpResponse(entity = HttpEntity.Chunked(
          ContentTypes.`application/octet-stream`,
          Source(List(
            HttpEntity.Chunk("foo"),
            HttpEntity.Chunk("bar"),
            HttpEntity.LastChunk(trailer = immutable.Seq[HttpHeader](RawHeader("Status", "grpc-status 10")))
          ))
        ))
        user.emitResponse(TheStreamId, response)
        network.expectDecodedHEADERS(streamId = TheStreamId, endStream = false)
        network.expectDATA(TheStreamId, endStream = false, ByteString("foobar"))
        network.expectDecodedHEADERS(streamId = TheStreamId).headers should be(immutable.Seq(RawHeader("status", "grpc-status 10")))
      }
      "include the trailing headers even when the buffer is emptied before sending the last chunk" inAssertAllStagesStopped new WaitingForResponseSetup {
        val queuePromise = Promise[SourceQueueWithComplete[HttpEntity.ChunkStreamPart]]()

        val response = HttpResponse(entity = HttpEntity.Chunked(
          ContentTypes.`application/octet-stream`,
          Source.queue[HttpEntity.ChunkStreamPart](100, OverflowStrategy.fail)
            .mapMaterializedValue(queuePromise.success(_))
        ))
        user.emitResponse(TheStreamId, response)
        val chunkQueue = Await.result(queuePromise.future, 10.seconds)

        chunkQueue.offer(HttpEntity.Chunk("foo"))
        chunkQueue.offer(HttpEntity.Chunk("bar"))

        network.expectDecodedHEADERS(streamId = TheStreamId, endStream = false)
        network.expectDATA(TheStreamId, endStream = false, ByteString("foobar"))

        chunkQueue.offer(HttpEntity.LastChunk(trailer = immutable.Seq[HttpHeader](RawHeader("Status", "grpc-status 10"))))
        chunkQueue.complete()
        network.expectDecodedHEADERS(streamId = TheStreamId).headers should be(immutable.Seq(RawHeader("status", "grpc-status 10")))
      }
      "send the trailing headers immediately, even when the stream window is depleted" inAssertAllStagesStopped new WaitingForResponseSetup {
        val queuePromise = Promise[SourceQueueWithComplete[HttpEntity.ChunkStreamPart]]()

        val response = HttpResponse(entity = HttpEntity.Chunked(
          ContentTypes.`application/octet-stream`,
          Source.queue[HttpEntity.ChunkStreamPart](100, OverflowStrategy.fail)
            .mapMaterializedValue(queuePromise.success(_))
        ))
        user.emitResponse(TheStreamId, response)

        val chunkQueue = Await.result(queuePromise.future, 10.seconds)

        def depleteWindow(): Unit = {
          val toSend: Int = network.remainingFromServerWindowFor(TheStreamId) min 1000
          if (toSend != 0) {
            val data = "x" * toSend
            Await.result(chunkQueue.offer(HttpEntity.Chunk(data)), 3.seconds)
            network.expectDATA(TheStreamId, endStream = false, ByteString(data))
            depleteWindow()
          }
        }

        network.expectDecodedHEADERS(streamId = TheStreamId, endStream = false)
        depleteWindow()

        chunkQueue.offer(HttpEntity.LastChunk(trailer = immutable.Seq[HttpHeader](RawHeader("grpc-status", "10"))))
        chunkQueue.complete()
        network.expectDecodedHEADERS(streamId = TheStreamId, endStream = true).headers should be(immutable.Seq(RawHeader("grpc-status", "10")))
      }
      "send the trailing headers even when last data chunk was delayed by window depletion" inAssertAllStagesStopped new WaitingForResponseSetup {
        val queuePromise = Promise[SourceQueueWithComplete[HttpEntity.ChunkStreamPart]]()

        val response = HttpResponse(entity = HttpEntity.Chunked(
          ContentTypes.`application/octet-stream`,
          Source.queue[HttpEntity.ChunkStreamPart](100, OverflowStrategy.fail)
            .mapMaterializedValue(queuePromise.success(_))
        ))
        user.emitResponse(TheStreamId, response)

        val chunkQueue = Await.result(queuePromise.future, 10.seconds)

        def depleteWindow(): Unit = {
          val toSend: Int = network.remainingFromServerWindowFor(TheStreamId) min 1000
          if (toSend != 0) {
            val data = "x" * toSend
            Await.result(chunkQueue.offer(HttpEntity.Chunk(data)), 3.seconds)
            network.expectDATA(TheStreamId, endStream = false, ByteString(data))
            depleteWindow()
          }
        }

        network.expectDecodedHEADERS(streamId = TheStreamId, endStream = false)
        depleteWindow()

        val lastData = ByteString("y" * 500)
        chunkQueue.offer(HttpEntity.Chunk(lastData)) // even out of connection window try to send one last chunk that will be buffered
        chunkQueue.offer(HttpEntity.LastChunk(trailer = immutable.Seq[HttpHeader](RawHeader("grpc-status", "10"))))
        chunkQueue.complete()

        network.toNet.request(1)
        // now increase windows somewhat but not over the buffered amount
        network.sendWINDOW_UPDATE(TheStreamId, 100)
        network.sendWINDOW_UPDATE(0, 100)
        network.expectDATA(TheStreamId, endStream = false, lastData.take(100))

        // now send the remaining data
        network.sendWINDOW_UPDATE(TheStreamId, 1000)
        network.sendWINDOW_UPDATE(0, 1000)
        network.expectDATA(TheStreamId, endStream = false, lastData.drop(100))

        network.expectDecodedHEADERS(streamId = TheStreamId, endStream = true).headers should be(immutable.Seq(RawHeader("grpc-status", "10")))
      }
    }

    "support multiple concurrent substreams" should {
      "receive two requests concurrently" inAssertAllStagesStopped new TestSetup with RequestResponseProbes {
        val request1 =
          HttpRequest(
            protocol = HttpProtocols.`HTTP/2.0`,
            entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, ""))

        network.sendRequestHEADERS(1, request1, endStream = false)

        val gotRequest1 = user.expectRequest()
        gotRequest1.withEntity(HttpEntity.Empty) shouldBe request1.withEntity(HttpEntity.Empty)
        val request1EntityProbe = ByteStringSinkProbe()
        gotRequest1.entity.dataBytes.runWith(request1EntityProbe.sink)

        val request2 =
          HttpRequest(
            protocol = HttpProtocols.`HTTP/2.0`,
            entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, ""))

        network.sendRequestHEADERS(3, request2, endStream = false)

        val gotRequest2 = user.expectRequest()
        gotRequest2.withEntity(HttpEntity.Empty) shouldBe request2.withEntity(HttpEntity.Empty)
        val request2EntityProbe = ByteStringSinkProbe()
        gotRequest2.entity.dataBytes.runWith(request2EntityProbe.sink)

        network.sendDATA(3, endStream = false, ByteString("abc"))
        request2EntityProbe.expectUtf8EncodedString("abc")

        network.sendDATA(1, endStream = false, ByteString("def"))
        request1EntityProbe.expectUtf8EncodedString("def")

        // now fail stream 2
        //sendRST_STREAM(3, ErrorCode.INTERNAL_ERROR)
        //request2EntityProbe.expectError()

        // make sure that other stream is not affected
        network.sendDATA(1, endStream = true, ByteString("ghi"))
        request1EntityProbe.expectUtf8EncodedString("ghi")
        request1EntityProbe.expectComplete()
      }
      "send two responses concurrently" inAssertAllStagesStopped new TestSetup with RequestResponseProbes {
        val theRequest = HttpRequest(protocol = HttpProtocols.`HTTP/2.0`)
        network.sendRequest(1, theRequest)
        user.expectRequest() shouldBe theRequest

        val entity1DataOut = TestPublisher.probe[ByteString]()
        val response1 = HttpResponse(entity = HttpEntity(ContentTypes.`application/octet-stream`, Source.fromPublisher(entity1DataOut)))
        user.emitResponse(1, response1)
        network.expectDecodedHEADERS(streamId = 1, endStream = false) shouldBe response1.withEntity(HttpEntity.Empty.withContentType(ContentTypes.`application/octet-stream`))

        def sendDataAndExpectOnNet(outStream: TestPublisher.Probe[ByteString], streamId: Int, dataString: String, endStream: Boolean = false): Unit = {
          val data = ByteString(dataString)
          if (dataString.nonEmpty) outStream.sendNext(data)
          if (endStream) outStream.sendComplete()
          if (data.nonEmpty || endStream) network.expectDATA(streamId, endStream = endStream, data)
        }

        sendDataAndExpectOnNet(entity1DataOut, 1, "abc")

        // send second request
        network.sendRequest(3, theRequest)
        user.expectRequest() shouldBe theRequest

        val entity2DataOut = TestPublisher.probe[ByteString]()
        val response2 = HttpResponse(entity = HttpEntity(ContentTypes.`application/octet-stream`, Source.fromPublisher(entity2DataOut)))
        user.emitResponse(3, response2)
        network.expectDecodedHEADERS(streamId = 3, endStream = false) shouldBe response2.withEntity(HttpEntity.Empty.withContentType(ContentTypes.`application/octet-stream`))

        // send again on stream 1
        sendDataAndExpectOnNet(entity1DataOut, 1, "zyx")

        // now send on stream 2
        sendDataAndExpectOnNet(entity2DataOut, 3, "mnopq")

        // now again on stream 1
        sendDataAndExpectOnNet(entity1DataOut, 1, "jklm")

        // send two data bits first but only pull and expect later
        entity1DataOut.sendNext(ByteString("hihihi"))
        entity2DataOut.sendNext(ByteString("hohoho"))
        network.expectDATA(1, endStream = false, ByteString("hihihi"))
        network.expectDATA(3, endStream = false, ByteString("hohoho"))

        // last data of stream 2
        sendDataAndExpectOnNet(entity2DataOut, 3, "uvwx", endStream = true)

        // also complete stream 1
        sendDataAndExpectOnNet(entity1DataOut, 1, "", endStream = true)
      }
      "receiving RST_STREAM for one of two sendable streams" inAssertAllStagesStopped new TestSetup with RequestResponseProbes {
        val theRequest = HttpRequest(protocol = HttpProtocols.`HTTP/2.0`)
        network.sendRequest(1, theRequest)
        user.expectRequest() shouldBe theRequest

        val entity1DataOut = TestPublisher.probe[ByteString]()
        val response1 = HttpResponse(entity = HttpEntity(ContentTypes.`application/octet-stream`, Source.fromPublisher(entity1DataOut)))
        user.emitResponse(1, response1)
        network.expectDecodedHEADERS(streamId = 1, endStream = false) shouldBe response1.withEntity(HttpEntity.Empty.withContentType(ContentTypes.`application/octet-stream`))

        def sendDataAndExpectOnNet(outStream: TestPublisher.Probe[ByteString], streamId: Int, dataString: String, endStream: Boolean = false): Unit = {
          val data = ByteString(dataString)
          if (dataString.nonEmpty) outStream.sendNext(data)
          if (endStream) outStream.sendComplete()
          if (data.nonEmpty || endStream) network.expectDATA(streamId, endStream = endStream, data)
        }

        sendDataAndExpectOnNet(entity1DataOut, 1, "abc")

        // send second request
        network.sendRequest(3, theRequest)
        user.expectRequest() shouldBe theRequest

        val entity2DataOut = TestPublisher.probe[ByteString]()
        val response2 = HttpResponse(entity = HttpEntity(ContentTypes.`application/octet-stream`, Source.fromPublisher(entity2DataOut)))
        user.emitResponse(3, response2)
        network.expectDecodedHEADERS(streamId = 3, endStream = false) shouldBe response2.withEntity(HttpEntity.Empty.withContentType(ContentTypes.`application/octet-stream`))

        // send again on stream 1
        sendDataAndExpectOnNet(entity1DataOut, 1, "zyx")

        // now send on stream 2
        sendDataAndExpectOnNet(entity2DataOut, 3, "mnopq")

        // now again on stream 1
        sendDataAndExpectOnNet(entity1DataOut, 1, "jklm")

        // send two data bits first but only pull and expect later
        entity1DataOut.sendNext(ByteString("hihihi"))
        entity2DataOut.sendNext(ByteString("hohoho"))

        network.sendRST_STREAM(1, ErrorCode.CANCEL)

        network.expectDATA(3, endStream = false, ByteString("hohoho"))

        // last data of stream 2
        sendDataAndExpectOnNet(entity2DataOut, 3, "uvwx", endStream = true)
      }
      "close substreams when connection is shutting down" inAssertAllStagesStopped StreamTestKit.assertAllStagesStopped(new TestSetup with RequestResponseProbes {
        val requestEntity = HttpEntity.Empty.withContentType(ContentTypes.`application/octet-stream`)
        val request = HttpRequest(entity = requestEntity)

        network.sendRequestHEADERS(1, request, false)
        val req = user.expectRequest()
        val reqProbe = ByteStringSinkProbe()
        req.entity.dataBytes.runWith(reqProbe.sink)

        val responseEntityProbe = TestPublisher.probe[ByteString]()
        val responseEntity = HttpEntity(ContentTypes.`application/octet-stream`, Source.fromPublisher(responseEntityProbe))
        val response = HttpResponse(200, entity = responseEntity)

        user.emitResponse(1, response)
        network.expectDecodedHEADERS(1, false)

        // check data flow for request entity
        network.sendDATA(1, false, ByteString("ping"))
        reqProbe.expectUtf8EncodedString("ping")

        // default settings will schedule a connection and a stream-level window update
        network.expectWindowUpdate()
        network.expectWindowUpdate()

        // check data flow for response entity
        responseEntityProbe.sendNext(ByteString("pong"))
        network.expectDATA(1, false, ByteString("pong"))

        network.fromNet.sendError(new RuntimeException("Connection broke"))

        // now all stream stages should be closed
        reqProbe.expectError().getMessage shouldBe "The HTTP/2 connection was shut down while the request was still ongoing"
        responseEntityProbe.expectCancellation()
      })
    }

    "respect flow-control" should {
      "not exceed connection-level window while sending" in pending
      "not exceed stream-level window while sending" in pending
      "not exceed stream-level window while sending after SETTINGS_INITIAL_WINDOW_SIZE changed" in pending
      "not exceed stream-level window while sending after SETTINGS_INITIAL_WINDOW_SIZE changed when window became negative through setting" in pending

      "reject WINDOW_UPDATE for connection with zero increment with PROTOCOL_ERROR" inAssertAllStagesStopped new TestSetup with RequestResponseProbes {
        network.sendWINDOW_UPDATE(0, 0) // illegal
        val (_, errorCode) = network.expectGOAWAY()

        errorCode should ===(ErrorCode.PROTOCOL_ERROR)
      }
      "reject WINDOW_UPDATE for stream with zero increment with PROTOCOL_ERROR" inAssertAllStagesStopped new TestSetup with RequestResponseProbes {
        // making sure we don't handle stream 0 and others differently here
        network.sendHEADERS(1, endStream = false, endHeaders = true, HPackSpecExamples.C41FirstRequestWithHuffman)
        network.sendWINDOW_UPDATE(1, 0) // illegal

        network.expectRST_STREAM(1, ErrorCode.PROTOCOL_ERROR)
      }

      "backpressure incoming frames when outgoing control frame buffer fills" inAssertAllStagesStopped new TestSetup with HandlerFunctionSupport {
        override def settings: ServerSettings = super.settings.mapHttp2Settings(_.withOutgoingControlFrameBufferSize(1))

        network.sendFrame(PingFrame(false, ByteString("abcdefgh")))
        // now one PING ack buffered
        // this one fills the input buffer between the probe and the server
        network.sendFrame(PingFrame(false, ByteString("abcdefgh")))

        // now there should be no new demand
        network.fromNet.pending shouldEqual 0
        network.fromNet.expectNoMessage(100.millis)
      }
    }

    "respect settings" should {
      "initial MAX_FRAME_SIZE" in pending

      "received non-zero length payload Settings with ACK flag (invalid 6.5)" inAssertAllStagesStopped new TestSetup with RequestResponseProbes {
        /*
         Receipt of a SETTINGS frame with the ACK flag set and a length field value other than 0
         MUST be treated as a connection error (Section 5.4.1) of type FRAME_SIZE_ERROR.
         */
        // we ACK the settings with an incorrect ACK (it must not have a payload)
        val ackFlag = new ByteFlag(0x1)
        val illegalPayload = hex"cafe babe"
        network.sendFrame(FrameType.SETTINGS, ackFlag, 0, illegalPayload)

        val (_, error) = network.expectGOAWAY()
        error should ===(ErrorCode.FRAME_SIZE_ERROR)
      }
      "received SETTINGS frame with a length other than a multiple of 6 octets (invalid 6_5)" inAssertAllStagesStopped new TestSetup with RequestResponseProbes {
        val data = hex"00 00 02 04 00 00 00 00 00"

        network.sendFrame(FrameType.SETTINGS, ByteFlag.Zero, 0, data)

        val (_, error) = network.expectGOAWAY()
        error should ===(ErrorCode.FRAME_SIZE_ERROR)
      }

      "received SETTINGS_MAX_FRAME_SIZE should cause outgoing DATA to be chunked up into at-most-that-size parts " inAssertAllStagesStopped new TestSetup with RequestResponseProbes {
        val maxSize = Math.pow(2, 15).toInt // 32768, valid value (between 2^14 and 2^24 - 1)
        network.sendSETTING(SettingIdentifier.SETTINGS_MAX_FRAME_SIZE, maxSize)

        network.expectSettingsAck()

        network.sendWINDOW_UPDATE(0, maxSize * 10) // make sure we can receive such large response, on connection

        network.sendHEADERS(1, endStream = false, endHeaders = true, HPackSpecExamples.C41FirstRequestWithHuffman)
        network.sendWINDOW_UPDATE(1, maxSize * 5) // make sure we can receive such large response, on this stream

        val theTooLargeByteString = ByteString("x" * (maxSize * 2))
        val tooLargeEntity = Source.single(theTooLargeByteString)
        val tooLargeResponse = HttpResponse(entity = HttpEntity(ContentTypes.`application/octet-stream`, tooLargeEntity))
        user.emitResponse(1, tooLargeResponse)

        network.expectHeaderBlock(1, endStream = false)
        // we receive the DATA in 2 parts, since the ByteString does not fit in a single frame
        val d1 = network.expectDATA(1, endStream = false, numBytes = maxSize)
        val d2 = network.expectDATA(1, endStream = true, numBytes = maxSize)
        d1.toList should have length maxSize
        d2.toList should have length maxSize
        (d1 ++ d2) should ===(theTooLargeByteString) // makes sure we received the parts in the right order
      }

      "received SETTINGS_MAX_CONCURRENT_STREAMS should limit the number of streams" inAssertAllStagesStopped new TestSetup with RequestResponseProbes {
        network.sendSETTING(SettingIdentifier.SETTINGS_MAX_CONCURRENT_STREAMS, 1)
        network.expectSettingsAck()

        // TODO actually apply the limiting and verify it works
        // This test is not required until supporting PUSH_PROMISE.
      }

      "not limit response streams even when the client send a SETTINGS_MAX_CONCURRENT_STREAMS" inAssertAllStagesStopped new TestSetup(
        Setting(SettingIdentifier.SETTINGS_MAX_CONCURRENT_STREAMS, 1)
      ) with RequestResponseProbes {
        def openStream(streamId: Int) = network.sendHEADERS(streamId, endStream = false, endHeaders = true, HPackSpecExamples.C41FirstRequestWithHuffman)
        def closeStream(streamId: Int) = user.emitResponse(streamId, HPackSpecExamples.FirstResponse)

        // client set SETTINGS_MAX_CONCURRENT_STREAMS to 1 so an attempt from the server to open more streams
        // should fail. But as long as the outgoing streams are a result of client-initiated communication
        // they should succeed.
        openStream(1)
        openStream(3)
        openStream(5)
        openStream(7)

        user.expectRequest()
        user.expectRequest()
        user.expectRequest()
        user.expectRequest()
        // there are 4 in-flight requests
        network.expectNoBytes(100.millis)

        closeStream(1)
        closeStream(3)
        closeStream(5)
        closeStream(7)
        network.expect[HeadersFrame]().streamId shouldBe (1)
        network.expect[HeadersFrame]().streamId shouldBe (3)
        network.expect[HeadersFrame]().streamId shouldBe (5)
        network.expect[HeadersFrame]().streamId shouldBe (7)
      }

      "received SETTINGS_HEADER_TABLE_SIZE" in new TestSetup with RequestResponseProbes {
        network.sendSETTING(SettingIdentifier.SETTINGS_HEADER_TABLE_SIZE, Math.pow(2, 15).toInt) // 32768, valid value (between 2^14 and 2^24 - 1)

        network.expectSettingsAck() // TODO check that the setting was indeed applied
      }

      "react on invalid SETTINGS_INITIAL_WINDOW_SIZE with FLOW_CONTROL_ERROR" inAssertAllStagesStopped new TestSetup with RequestResponseProbes {
        // valid values are below 2^31 - 1 for int, which actually just means positive
        network.sendSETTING(SettingIdentifier.SETTINGS_INITIAL_WINDOW_SIZE, -1)

        val (_, code) = network.expectGOAWAY()
        code should ===(ErrorCode.FLOW_CONTROL_ERROR)
      }
    }

    "enforce settings" should {

      "reject new substreams when exceeding SETTINGS_MAX_CONCURRENT_STREAMS" inAssertAllStagesStopped new TestSetup with RequestResponseProbes {
        def maxStreams: Int = 16
        override def settings: ServerSettings = super.settings.mapHttp2Settings(_.withMaxConcurrentStreams(maxStreams))
        val requestHeaderBlock: ByteString = HPackSpecExamples.C41FirstRequestWithHuffman

        // start as many streams as max concurrent...
        private val streamIds: IndexedSeq[Int] = (0 until maxStreams).map(id => 1 + id * 2)
        streamIds.foreach(streamId =>
          network.sendHEADERS(streamId, endStream = false, endHeaders = true, requestHeaderBlock)
        )

        // while we don't exceed the limit, there's silence on the line
        network.expectNoBytes(100.millis)

        // When we exceed the limit, though...
        val lastValidStreamId = streamIds.max
        val firstInvalidStreamId = lastValidStreamId + 2
        network.sendHEADERS(firstInvalidStreamId, endStream = false, endHeaders = true, requestHeaderBlock)
        network.expectRST_STREAM(firstInvalidStreamId, ErrorCode.REFUSED_STREAM)
      }

      "reject new substreams when exceeding SETTINGS_MAX_CONCURRENT_STREAMS (with closed streams in between)" inAssertAllStagesStopped new TestSetup with RequestResponseProbes {
        def maxStreams: Int = 32
        val requestHeaderBlock: ByteString = HPackSpecExamples.C41FirstRequestWithHuffman
        override def settings = super.settings.mapHttp2Settings(_.withMaxConcurrentStreams(maxStreams))

        // the Seq of stream ids has gaps
        // the skipped values should be represented as automatically-closed streams
        private val streamIds: IndexedSeq[Int] = (0 until maxStreams).map(id => 1 + id * 4)
        streamIds.foreach { streamId =>
          network.sendHEADERS(streamId, endStream = false, endHeaders = true, requestHeaderBlock)
        }

        // while we don't exceed the limit, there's silence on the line
        network.expectNoBytes(100.millis)

        // When we exceed the limit, though...
        val lastValidStreamId = streamIds.max
        val firstInvalidStreamId = lastValidStreamId + 2
        network.sendHEADERS(firstInvalidStreamId, endStream = false, endHeaders = true, requestHeaderBlock)
        network.expectRST_STREAM(firstInvalidStreamId, ErrorCode.REFUSED_STREAM)
      }

    }

    "support low-level features" should {
      "respond to PING frames (spec 6_7)" inAssertAllStagesStopped new TestSetup with RequestResponseProbes {
        network.sendFrame(FrameType.PING, new ByteFlag(0x0), 0, ByteString("data1234")) // ping frame data must be of size 8

        val (flag, payload) = network.expectFrameFlagsAndPayload(FrameType.PING, 0) // must be on stream 0
        flag should ===(new ByteFlag(0x1)) // a PING response
        payload should ===(ByteString("data1234"))
      }
      "NOT respond to PING ACK frames (spec 6_7)" inAssertAllStagesStopped new TestSetup with RequestResponseProbes {
        val AckFlag = new ByteFlag(0x1)
        network.sendFrame(FrameType.PING, AckFlag, 0, ConfigurablePing.Ping.data) // other ack payload than this causes GOAWAY

        network.expectNoBytes(100.millis)
      }
      "respond to invalid (not 0x0 streamId) PING with GOAWAY PROTOCOL_ERROR (spec 6_7)" inAssertAllStagesStopped new TestSetup with RequestResponseProbes {
        val invalidIdForPing = 1
        network.sendFrame(FrameType.PING, ByteFlag.Zero, invalidIdForPing, ByteString("abcd1234"))

        val (_, errorCode) = network.expectGOAWAY()
        errorCode should ===(ErrorCode.PROTOCOL_ERROR)
      }
      "respond to invalid (length smaller than 8) with GOAWAY FRAME_SIZE_ERROR (spec 6_7)" inAssertAllStagesStopped new TestSetup with RequestResponseProbes {
        network.sendFrame(FrameType.PING, ByteFlag.Zero, 0x0, ByteString("abcd123"))

        val (_, errorCode) = network.expectGOAWAY()
        errorCode should ===(ErrorCode.FRAME_SIZE_ERROR)
      }
      "respond to invalid (length larger than 8) with GOAWAY FRAME_SIZE_ERROR (spec 6_7)" inAssertAllStagesStopped new TestSetup with RequestResponseProbes {
        network.sendFrame(FrameType.PING, ByteFlag.Zero, 0x0, ByteString("abcd12345"))

        val (_, errorCode) = network.expectGOAWAY()
        errorCode should ===(ErrorCode.FRAME_SIZE_ERROR)
      }
      "respond to PING frames giving precedence over any other kind pending frame" in pending
      "acknowledge SETTINGS frames" in pending
    }

    "respect the substream state machine" should {
      abstract class SimpleRequestResponseRoundtripSetup extends TestSetup with RequestResponseProbes

      "reject other frame than HEADERS/PUSH_PROMISE in idle state with connection-level PROTOCOL_ERROR (5.1)" inAssertAllStagesStopped new SimpleRequestResponseRoundtripSetup {
        network.sendDATA(9, endStream = true, HPackSpecExamples.C41FirstRequestWithHuffman)
        network.expectGOAWAY()
      }
      "reject incoming frames on already half-closed substream" in pending

      "reject even-numbered client-initiated substreams" in pending /* new SimpleRequestResponseRoundtripSetup {
        network.sendHEADERS(2, endStream = true, endHeaders = true, HPackSpecExamples.C41FirstRequestWithHuffman)
        network.expectGOAWAY()
        // after GOAWAY we expect graceful completion after x amount of time
        // TODO: completion logic, wait?!
        expectGracefulCompletion()
      }
      */

      "reject all other frames while waiting for CONTINUATION frames" in pending

      "accept trailing request HEADERS" inAssertAllStagesStopped new SimpleRequestResponseRoundtripSetup {
        network.sendHEADERS(1, endStream = false, endHeaders = true, HPackSpecExamples.C41FirstRequestWithHuffman)
        network.sendHEADERS(1, endStream = true, Seq(RawHeader("grpc-status", "0")))

        // On the server side we read the entity as bytes, so the trailing headers are not available.
        user.expectRequest()
      }

      "reject HEADERS for already closed streams" inAssertAllStagesStopped new SimpleRequestResponseRoundtripSetup {
        network.sendHEADERS(1, endStream = true, endHeaders = true, HPackSpecExamples.C41FirstRequestWithHuffman)
        network.sendHEADERS(1, endStream = true, endHeaders = true, HPackSpecExamples.C41FirstRequestWithHuffman)
        network.expectGOAWAY()
      }

      "reject mid-stream HEADERS with endStream = false" inAssertAllStagesStopped new SimpleRequestResponseRoundtripSetup {
        network.sendHEADERS(1, endStream = false, endHeaders = true, HPackSpecExamples.C41FirstRequestWithHuffman)
        network.sendHEADERS(1, endStream = false, endHeaders = true, HPackSpecExamples.C41FirstRequestWithHuffman)
        network.expectGOAWAY()
      }

      "reject substream creation for streams invalidated by skipped substream IDs" inAssertAllStagesStopped new SimpleRequestResponseRoundtripSetup {
        network.sendHEADERS(9, endStream = true, endHeaders = true, HPackSpecExamples.C41FirstRequestWithHuffman)
        network.sendHEADERS(1, endStream = true, endHeaders = true, HPackSpecExamples.C41FirstRequestWithHuffman)
        network.expectGOAWAY()
      }
    }

    "expose synthetic headers" should {
      "expose Remote-Address" inAssertAllStagesStopped new TestSetup with RequestResponseProbes {

        lazy val theAddress = "127.0.0.1"
        lazy val thePort = 1337
        override def modifyServer(server: BidiFlow[HttpResponse, ByteString, ByteString, HttpRequest, ServerTerminator]) =
          BidiFlow.fromGraph(server.withAttributes(
            HttpAttributes.remoteAddress(new InetSocketAddress(theAddress, thePort))
          ))

        val target = Uri("http://www.example.com/")
        network.sendRequest(1, HttpRequest(uri = target))
        user.requestIn.ensureSubscription()

        val request = user.expectRequestRaw()
        @nowarn("msg=deprecated")
        val remoteAddressHeader = request.header[headers.`Remote-Address`].get
        remoteAddressHeader.address.getAddress.get().toString shouldBe ("/" + theAddress)
        remoteAddressHeader.address.getPort shouldBe thePort
      }

      "expose Tls-Session-Info" inAssertAllStagesStopped new TestSetup with RequestResponseProbes {
        override def settings: ServerSettings =
          super.settings.withParserSettings(super.settings.parserSettings.withIncludeTlsSessionInfoHeader(true))

        lazy val expectedSession = SSLContext.getDefault.createSSLEngine.getSession
        override def modifyServer(server: BidiFlow[HttpResponse, ByteString, ByteString, HttpRequest, ServerTerminator]) =
          BidiFlow.fromGraph(server.withAttributes(
            HttpAttributes.tlsSessionInfo(expectedSession)
          ))

        val target = Uri("http://www.example.com/")
        network.sendRequest(1, HttpRequest(uri = target))
        user.requestIn.ensureSubscription()

        val request = user.expectRequestRaw()
        val tlsSessionInfoHeader = request.header[headers.`Tls-Session-Info`].get
        tlsSessionInfoHeader.session shouldBe expectedSession
      }
    }

    "must not swallow errors / warnings" in pending

    "support for configurable pings" should {
      "send pings when there is an active but slow stream to server" in StreamTestKit.assertAllStagesStopped(new TestSetup with RequestResponseProbes {
        override def settings: ServerSettings = {
          val default = super.settings
          default.withHttp2Settings(default.http2Settings.withPingInterval(500.millis))
        }

        network.sendRequestHEADERS(1, HttpRequest(protocol = HttpProtocols.`HTTP/2.0`), endStream = false)
        user.expectRequest()

        network.expectNoBytes(250.millis) // no data for 500ms interval should trigger ping (but client counts from emitting last frame, so it's not really 500ms here)
        network.expectFrame(FrameType.PING, ByteFlag.Zero, 0, ConfigurablePing.Ping.data)

        network.toNet.cancel()
      })

      "send pings when there is an active but slow stream from server" in StreamTestKit.assertAllStagesStopped(new TestSetup with RequestResponseProbes {

        override def settings: ServerSettings = {
          val default = super.settings
          default.withHttp2Settings(default.http2Settings.withPingInterval(500.millis))
        }

        val theRequest = HttpRequest(protocol = HttpProtocols.`HTTP/2.0`)
        network.sendRequest(1, theRequest)
        user.expectRequest() shouldBe theRequest

        val responseStream = TestPublisher.probe[ByteString]()
        val response1 = HttpResponse(entity = HttpEntity(ContentTypes.`application/octet-stream`, Source.fromPublisher(responseStream)))
        user.emitResponse(1, response1)
        network.expectDecodedHEADERS(streamId = 1, endStream = false) shouldBe response1.withEntity(HttpEntity.Empty.withContentType(ContentTypes.`application/octet-stream`))
        network.expectNoBytes(250.millis) // no data for 500ms interval should trigger ping (but client counts from emitting last frame, so it's not really 500ms here)
        network.expectFrame(FrameType.PING, ByteFlag.Zero, 0, ConfigurablePing.Ping.data)

        network.toNet.cancel()
      })

      "send GOAWAY when ping times out" in StreamTestKit.assertAllStagesStopped(new TestSetup with RequestResponseProbes {
        override def settings: ServerSettings = {
          val default = super.settings
          default.withHttp2Settings(default.http2Settings.withPingInterval(800.millis).withPingTimeout(400.millis))
        }

        network.sendRequestHEADERS(1, HttpRequest(protocol = HttpProtocols.`HTTP/2.0`), endStream = false)
        user.expectRequest()

        network.expectNoBytes(400.millis) // no data for 800ms interval should trigger ping (but client counts from emitting last frame, so it's not really 800ms here)
        network.expectFrame(FrameType.PING, ByteFlag.Zero, 0, ConfigurablePing.Ping.data)
        network.expectNoBytes(200.millis) // timeout is 400ms from client emitting ping, (so not really 400ms here)
        val (_, errorCode) = network.expectGOAWAY(1)
        errorCode should ===(ErrorCode.PROTOCOL_ERROR)

        // FIXME should also verify close of connection, but it isn't implemented yet
        network.toNet.cancel()
      })

    }
    "support graceful shutdown" should {
      "immediately shutdown when no requests are pending" inAssertAllStagesStopped new TestSetup with RequestResponseProbes {
        val terminated = serverTerminator.terminate(1.minute)
        network.expectGOAWAY()
        network.expectComplete()
        terminated.futureValue
      }
      "close after current requests have been processed (while request data was outstanding)" inAssertAllStagesStopped new TestSetup with RequestResponseProbes {
        network.sendRequestHEADERS(1, HttpRequest(), endStream = false)
        val req = user.expectRequest()
        val p = ByteStringSinkProbe(req.entity.dataBytes)
        network.sendDATA(1, endStream = false, ByteString("abc"))
        p.expectUtf8EncodedString("abc")

        // now terminate
        val terminated = serverTerminator.terminate(1.minute)
        network.expectWindowUpdate()
        network.expectWindowUpdate()
        network.expectGOAWAY(1)
        network.sendDATA(1, endStream = true, ByteString("def"))
        p.expectUtf8EncodedString("def")
        p.expectComplete()

        user.emitResponse(1, HttpResponse())
        network.expectDecodedHEADERS(1)

        network.expectComplete()
        terminated.futureValue
      }
      "close after current requests have been processed (when response was outstanding)" inAssertAllStagesStopped new TestSetup with RequestResponseProbes {
        network.sendRequest(1, HttpRequest())
        user.expectRequest()
        val terminated = serverTerminator.terminate(1.minute)
        network.expectGOAWAY(1)
        user.emitResponse(1, HttpResponse())
        network.expectDecodedHEADERS(1)

        network.expectComplete()
        terminated.futureValue
      }
      "close after current requests have been processed (while response data was outstanding)" inAssertAllStagesStopped new TestSetup with RequestResponseProbes {
        network.sendRequest(1, HttpRequest())
        user.expectRequest()

        val dataStream = TestPublisher.probe[ByteString]()
        user.emitResponse(1, HttpResponse(entity = HttpEntity(ContentTypes.`application/octet-stream`, Source.fromPublisher(dataStream))))
        network.expectDecodedHEADERS(1, endStream = false)
        dataStream.sendNext(ByteString("abc"))
        network.expectDATA(1, endStream = false, ByteString("abc"))

        val terminated = serverTerminator.terminate(1.minute)
        network.expectGOAWAY(1)
        dataStream.sendNext(ByteString("def"))
        network.expectDATA(1, endStream = false, ByteString("def"))
        dataStream.sendComplete()
        network.expectDATA(1, endStream = true, ByteString.empty)

        network.expectComplete()
        terminated.futureValue
      }
      "refuse late incoming requests" inAssertAllStagesStopped new TestSetup with RequestResponseProbes {
        network.sendRequest(1, HttpRequest())
        user.expectRequest()

        // now terminate
        val terminated = serverTerminator.terminate(1.minute)
        network.expectGOAWAY(1)

        // send another request
        network.sendRequest(3, HttpRequest())
        network.expectRST_STREAM(3, ErrorCode.REFUSED_STREAM)

        // no complete work on connection
        user.emitResponse(1, HttpResponse())
        network.expectDecodedHEADERS(1)

        network.expectComplete()
        terminated.futureValue
      }
      "finally close after deadline" inAssertAllStagesStopped new TestSetup with RequestResponseProbes {
        network.sendRequest(1, HttpRequest())
        user.expectRequest()

        // now terminate
        val terminated = serverTerminator.terminate(10.millis)
        network.expectGOAWAY(1)

        network.expectComplete()
        terminated.futureValue
      }
      "finally close after deadline (while closing request stream)" inAssertAllStagesStopped new TestSetup with RequestResponseProbes {
        network.sendRequestHEADERS(1, HttpRequest(), endStream = false)
        val req = user.expectRequest()
        val p = ByteStringSinkProbe(req.entity.dataBytes)
        network.sendDATA(1, endStream = false, ByteString("abc"))
        p.expectUtf8EncodedString("abc")

        // now terminate
        val terminated = serverTerminator.terminate(10.millis)
        network.expectWindowUpdate()
        network.expectWindowUpdate()
        network.expectGOAWAY(1)

        network.expectComplete()
        p.expectError()
        terminated.futureValue
      }
      "finally close after deadline (while cancelling response stream)" inAssertAllStagesStopped new TestSetup with RequestResponseProbes {
        network.sendRequest(1, HttpRequest())
        user.expectRequest()

        val dataStream = TestPublisher.probe[ByteString]()
        user.emitResponse(1, HttpResponse(entity = HttpEntity(ContentTypes.`application/octet-stream`, Source.fromPublisher(dataStream))))
        network.expectDecodedHEADERS(1, endStream = false)
        dataStream.sendNext(ByteString("abc"))
        network.expectDATA(1, endStream = false, ByteString("abc"))

        val terminated = serverTerminator.terminate(10.millis)
        network.expectGOAWAY(1)

        network.expectComplete()
        dataStream.expectCancellation()
        terminated.futureValue
      }
    }
  }

  implicit class InWithStoppedStages(name: String) {
    def inAssertAllStagesStopped(runTest: => TestSetup) =
      name in StreamTestKit.assertAllStagesStopped {
        val setup = runTest

        // force connection to shutdown (in case it is an invalid state)
        setup.network.fromNet.sendError(new RuntimeException)
        setup.network.toNet.cancel()

        // and then assert that all stages, substreams in particular, are stopped
      }
  }

  protected /* To make ByteFlag warnings go away */ abstract class TestSetupWithoutHandshake {
    implicit def ec: ExecutionContext = system.dispatcher

    private val framesOut: Http2FrameProbe = Http2FrameProbe()
    private val toNet = framesOut.plainDataProbe
    private val fromNet = TestPublisher.probe[ByteString]()

    def handlerFlow: Flow[HttpRequest, HttpResponse, NotUsed]

    // hook to modify server, for example add attributes
    def modifyServer(server: BidiFlow[HttpResponse, ByteString, ByteString, HttpRequest, ServerTerminator]) = server

    // hook to modify server settings
    def settings: ServerSettings = ServerSettings(system).withServerHeader(None)

    final def theServer: BidiFlow[HttpResponse, ByteString, ByteString, HttpRequest, ServerTerminator] =
      modifyServer(Http2Blueprint.serverStack(settings, system.log, telemetry = NoOpTelemetry, dateHeaderRendering = Http().dateHeaderRendering))
        .atop(LogByteStringTools.logByteStringBidi("network-plain-text").addAttributes(Attributes(LogLevels(Logging.DebugLevel, Logging.DebugLevel, Logging.DebugLevel))))

    val serverTerminator =
      handlerFlow
        .joinMat(theServer)(Keep.right)
        .join(Flow.fromSinkAndSource(toNet.sink, Source.fromPublisher(fromNet)))
        .withAttributes(Attributes.inputBuffer(1, 1))
        .run()

    val network = new NetworkSide(fromNet, toNet, framesOut) with Http2FrameHpackSupport
  }

  class NetworkSide(val fromNet: Probe[ByteString], val toNet: ByteStringSinkProbe, val framesOut: Http2FrameProbe) extends WindowTracking {
    override def frameProbeDelegate = framesOut

    def sendBytes(bytes: ByteString): Unit = fromNet.sendNext(bytes)

  }

  /** Basic TestSetup that has already passed the exchange of the connection preface */
  abstract class TestSetup(initialClientSettings: Setting*) extends TestSetupWithoutHandshake {
    network.sendBytes(Http2Protocol.ClientConnectionPreface)
    network.expectSETTINGS()

    network.sendFrame(SettingsFrame(immutable.Seq.empty ++ initialClientSettings))
    network.expectSettingsAck()
  }

  /** Provides the user handler flow as `requestIn` and `responseOut` probes for manual stream interaction */
  trait RequestResponseProbes extends TestSetupWithoutHandshake {
    private lazy val requestIn = TestSubscriber.probe[HttpRequest]()
    private lazy val responseOut = TestPublisher.probe[HttpResponse]()

    def handlerFlow: Flow[HttpRequest, HttpResponse, NotUsed] =
      Flow.fromSinkAndSource(Sink.fromSubscriber(requestIn), Source.fromPublisher(responseOut))

    lazy val user = new UserSide(requestIn, responseOut)

    def expectGracefulCompletion(): Unit = {
      network.toNet.expectComplete()
      user.requestIn.expectComplete()
    }
  }

  class UserSide(val requestIn: TestSubscriber.Probe[HttpRequest], val responseOut: TestPublisher.Probe[HttpResponse]) {
    def expectRequest(): HttpRequest = requestIn.requestNext().removeAttribute(Http2.streamId)
    def expectRequestRaw(): HttpRequest = requestIn.requestNext() // TODO, make it so that internal headers are not listed in `headers` etc?
    def emitResponse(streamId: Int, response: HttpResponse): Unit =
      responseOut.sendNext(response.addAttribute(Http2.streamId, streamId))

  }

  /** Provides the user handler flow as a handler function */
  trait HandlerFunctionSupport extends TestSetupWithoutHandshake {
    def parallelism: Int = 2
    def handler: HttpRequest => Future[HttpResponse] =
      _ => Future.successful(HttpResponse())

    def handlerFlow: Flow[HttpRequest, HttpResponse, NotUsed] =
      Http2Blueprint.handleWithStreamIdHeader(parallelism)(handler)
  }

  def bytes(num: Int, byte: Byte): ByteString = ByteString(Array.fill[Byte](num)(byte))
}
