/*
 * Copyright (C) 2018-2020 Lightbend Inc. <https://www.lightbend.com>
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
import akka.http.impl.engine.server.HttpAttributes
import akka.http.impl.engine.ws.ByteStringSinkProbe
import akka.http.impl.util.AkkaSpecWithMaterializer
import akka.http.impl.util.LogByteStringTools
import akka.http.scaladsl.model.HttpEntity.{ Chunked, LastChunk }
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.CacheDirectives
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.settings.ServerSettings
import akka.stream.Attributes
import akka.stream.Attributes.LogLevels
import akka.stream.OverflowStrategy
import akka.stream.impl.io.ByteStringParser.ByteReader
import akka.stream.scaladsl.BidiFlow
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.SourceQueueWithComplete
import akka.stream.testkit.TestPublisher.ManualProbe
import akka.stream.testkit.scaladsl.StreamTestKit
import akka.stream.testkit.TestPublisher
import akka.stream.testkit.TestSubscriber
import akka.testkit._
import akka.util.ByteString
import com.github.ghik.silencer.silent
import javax.net.ssl.SSLContext
import org.scalatest.concurrent.Eventually
import org.scalatest.concurrent.PatienceConfiguration.Timeout

import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.Await
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
  with WithInPendingUntilFixed with Eventually {
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
          sendHEADERS(streamId, endStream = true, endHeaders = true, requestHeaderBlock)
          expectRequest() shouldBe expectedRequest

          responseOut.sendNext(response.addAttribute(Http2.streamId, streamId))
          val headerPayload = expectHeaderBlock(streamId)
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
        sendHEADERS(1, endStream = false, endHeaders = true, headerBlockFragment = headerBlock)

        val (_, errorCode) = expectGOAWAY(0) // since we have not processed any stream
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
        sendHEADERS(3, endStream = false, endHeaders = true, headerBlockFragment = incorrectHeaderBlock)

        val (_, errorCode) = expectGOAWAY(1) // since we have successfully started processing stream `1`
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

        sendHEADERS(1, endStream = true, endHeaders = false, fragment1)
        requestIn.ensureSubscription()
        requestIn.expectNoMessage(100.millis)
        sendCONTINUATION(1, endHeaders = true, fragment2)

        val request = expectRequestRaw()

        request.method shouldBe HttpMethods.GET
        request.uri shouldBe Uri("http://www.example.com/")

        val streamId = request.attribute(Http2.streamId).getOrElse(Http2Compliance.missingHttpIdHeaderException)
        responseOut.sendNext(HPackSpecExamples.FirstResponse.addAttribute(Http2.streamId, streamId))
        val headerPayload = expectHeaderBlock(1)
        headerPayload shouldBe HPackSpecExamples.C61FirstResponseWithHuffman
      }
      "GET request in one HEADERS and two CONTINUATION frames" inAssertAllStagesStopped new TestSetup with RequestResponseProbes {
        val headerBlock = HPackSpecExamples.C41FirstRequestWithHuffman
        val fragment1 = headerBlock.take(8) // must be grouped by octets
        val fragment2 = headerBlock.drop(8).take(8)
        val fragment3 = headerBlock.drop(16)

        sendHEADERS(1, endStream = true, endHeaders = false, fragment1)
        requestIn.ensureSubscription()
        requestIn.expectNoMessage(100.millis)
        sendCONTINUATION(1, endHeaders = false, fragment2)
        sendCONTINUATION(1, endHeaders = true, fragment3)

        val request = expectRequestRaw()

        request.method shouldBe HttpMethods.GET
        request.uri shouldBe Uri("http://www.example.com/")

        val streamId = request.attribute(Http2.streamId).getOrElse(Http2Compliance.missingHttpIdHeaderException)
        responseOut.sendNext(HPackSpecExamples.FirstResponse.addAttribute(Http2.streamId, streamId))
        val headerPayload = expectHeaderBlock(1)
        headerPayload shouldBe HPackSpecExamples.C61FirstResponseWithHuffman
      }

      "fail if Http2StreamIdHeader missing" in pending
      "automatically add `Date` header" in pending

      "parse headers to modeled headers" inAssertAllStagesStopped new TestSetup with RequestResponseProbes with Http2FrameHpackSupport {
        import CacheDirectives._
        import headers._
        val expectedRequest = HttpRequest(
          method = HttpMethods.GET,
          uri = "http://www.example.com/",
          headers = Vector(`Cache-Control`(`no-cache`), `Cache-Control`(`max-age`(1000)), `Access-Control-Allow-Origin`.`*`),
          protocol = HttpProtocols.`HTTP/2.0`)
        val streamId = 1
        val requestHeaderBlock = encodeRequestHeaders(HttpRequest(
          method = HttpMethods.GET,
          uri = "http://www.example.com/",
          headers = Vector(
            RawHeader("cache-control", "no-cache"),
            RawHeader("cache-control", "max-age=1000"),
            RawHeader("access-control-allow-origin", "*")),
          protocol = HttpProtocols.`HTTP/2.0`))
        sendHEADERS(streamId, endStream = true, endHeaders = true, requestHeaderBlock)
        expectRequest().headers shouldBe expectedRequest.headers
      }

      "acknowledge change to SETTINGS_HEADER_TABLE_SIZE in next HEADER frame" inAssertAllStagesStopped new TestSetup with RequestResponseProbes {
        sendSETTING(SettingIdentifier.SETTINGS_HEADER_TABLE_SIZE, 8192)
        expectSettingsAck()

        val headerBlock = HPackSpecExamples.C41FirstRequestWithHuffman

        sendHEADERS(1, endStream = true, endHeaders = true, headerBlock)
        requestIn.ensureSubscription()
        requestIn.expectNoMessage(100.millis)

        val request = expectRequestRaw()

        request.method shouldBe HttpMethods.GET
        request.uri shouldBe Uri("http://www.example.com/")

        val streamId = request.attribute(Http2.streamId).getOrElse(Http2Compliance.missingHttpIdHeaderException)
        responseOut.sendNext(HPackSpecExamples.FirstResponse.addAttribute(Http2.streamId, streamId))
        val headerPayload = expectHeaderBlock(1)

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

    abstract class RequestEntityTestSetup extends TestSetup with RequestResponseProbes with Http2FrameHpackSupport {
      val TheStreamId = 1
      protected def sendRequest(): Unit

      sendRequest()
      val receivedRequest = expectRequest()
      val entityDataIn = ByteStringSinkProbe()
      receivedRequest.entity.dataBytes.runWith(entityDataIn.sink)
      entityDataIn.ensureSubscription()
    }

    abstract class WaitingForRequest(request: HttpRequest) extends RequestEntityTestSetup {
      protected def sendRequest(): Unit = sendRequest(TheStreamId, request)
    }
    abstract class WaitingForRequestData extends RequestEntityTestSetup {
      lazy val request = HttpRequest(method = HttpMethods.POST, uri = "https://example.com/upload", protocol = HttpProtocols.`HTTP/2.0`)

      protected def sendRequest(): Unit =
        sendRequestHEADERS(TheStreamId, request, endStream = false)

      def sendWindowFullOfData(): Int = {
        val dataLength = remainingToServerWindowFor(TheStreamId)
        sendDATA(TheStreamId, endStream = false, ByteString(Array.fill[Byte](dataLength)(23)))
        dataLength
      }
    }

    "support stream for request entity data" should {
      "send data frames to entity stream" inAssertAllStagesStopped new WaitingForRequestData {
        val data1 = ByteString("abcdef")
        sendDATA(TheStreamId, endStream = false, data1)
        entityDataIn.expectBytes(data1)

        val data2 = ByteString("zyxwvu")
        sendDATA(TheStreamId, endStream = false, data2)
        entityDataIn.expectBytes(data2)

        val data3 = ByteString("mnopq")
        sendDATA(TheStreamId, endStream = true, data3)
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
        receivedRequest.entity.isIndefiniteLength should ===(false)
        receivedRequest.entity.contentLengthOption should ===(Some(1337L))
        entityDataIn.expectBytes(ByteString("x" * 1337))
        entityDataIn.expectComplete()
      }
      "fail entity stream if peer sends RST_STREAM frame" inAssertAllStagesStopped new WaitingForRequestData {
        val data1 = ByteString("abcdef")
        sendDATA(TheStreamId, endStream = false, data1)
        entityDataIn.expectBytes(data1)

        sendRST_STREAM(TheStreamId, ErrorCode.INTERNAL_ERROR)
        val error = entityDataIn.expectError()
        error.getMessage shouldBe "Stream with ID [1] was closed by peer with code INTERNAL_ERROR(0x02)"
      }

      // Reproducing https://github.com/akka/akka-http/issues/2957
      "close the stream when we receive a RST after we have half-closed ourselves as well" inAssertAllStagesStopped new WaitingForRequestData {
        // Client sends the request, but doesn't close the stream yet. This is a bit weird, but it's whet grpcurl does ;)
        sendDATA(streamId = TheStreamId, endStream = false, ByteString(0, 0, 0, 0, 0x10, 0x22, 0x0e) ++ ByteString.fromString("GreeterService"))

        // We emit a 404 response, half-closing the stream.
        emitResponse(streamId = TheStreamId, HttpResponse(StatusCodes.NotFound))

        // The client closes the stream with a protocol error. This is somewhat questionable but it's what grpc-go does
        sendRST_STREAM(streamId = TheStreamId, ErrorCode.PROTOCOL_ERROR)
        entityDataIn.expectError()
        // Wait to give the warning (that we hope not to see) time to pop up.
        Thread.sleep(100)
      }
      "not fail the whole connection when one stream is RST twice" inAssertAllStagesStopped new WaitingForRequestData {
        sendRST_STREAM(TheStreamId, ErrorCode.STREAM_CLOSED)
        val error = entityDataIn.expectError()
        error.getMessage shouldBe "Stream with ID [1] was closed by peer with code STREAM_CLOSED(0x05)"
        expectNoBytes(100.millis)

        // https://http2.github.io/http2-spec/#StreamStates
        // Endpoints MUST ignore WINDOW_UPDATE or RST_STREAM frames received in this state,
        sendRST_STREAM(TheStreamId, ErrorCode.STREAM_CLOSED)
        expectNoBytes(100.millis)
      }
      "not fail the whole connection when data frames are received after stream was cancelled" inAssertAllStagesStopped new WaitingForRequestData {
        entityDataIn.cancel()
        expectRST_STREAM(TheStreamId)
        sendDATA(TheStreamId, endStream = false, ByteString("test"))
        // should just be ignored, especially no GOAWAY frame should be sent in response
        expectNoBytes(100.millis)
      }
      "send RST_STREAM if entity stream is canceled" inAssertAllStagesStopped new WaitingForRequestData {
        val data1 = ByteString("abcdef")
        sendDATA(TheStreamId, endStream = false, data1)
        entityDataIn.expectBytes(data1)

        pollForWindowUpdates(10.millis)

        entityDataIn.cancel()
        expectRST_STREAM(TheStreamId, ErrorCode.CANCEL)
      }
      "send out WINDOW_UPDATE frames when request data is read so that the stream doesn't stall" inAssertAllStagesStopped new WaitingForRequestData {
        (1 to 10).foreach { _ =>
          val bytesSent = sendWindowFullOfData()
          bytesSent should be > 0
          entityDataIn.expectBytes(bytesSent)
          pollForWindowUpdates(10.millis)
          remainingToServerWindowFor(TheStreamId) should be > 0
        }
      }
      "backpressure until request entity stream is read (don't send out unlimited WINDOW_UPDATE before)" inAssertAllStagesStopped new WaitingForRequestData {
        var totallySentBytes = 0
        // send data until we don't receive any window updates from the implementation any more
        eventually(Timeout(1.second.dilated)) {
          totallySentBytes += sendWindowFullOfData()
          // the implementation may choose to send a few window update until internal buffers are filled
          pollForWindowUpdates(10.millis)
          remainingToServerWindowFor(TheStreamId) shouldBe 0
        }

        // now drain entity source
        entityDataIn.expectBytes(totallySentBytes)

        eventually(Timeout(1.second.dilated)) {
          pollForWindowUpdates(10.millis)
          remainingToServerWindowFor(TheStreamId) should be > 0
        }
      }
      "send data frames to entity stream and ignore trailing headers" inAssertAllStagesStopped new WaitingForRequestData {
        val data1 = ByteString("abcdef")
        sendDATA(TheStreamId, endStream = false, data1)
        entityDataIn.expectBytes(data1)

        sendHEADERS(TheStreamId, endStream = true, Seq(headers.`Cache-Control`(CacheDirectives.`no-cache`)))
        entityDataIn.expectComplete()
      }

      "fail if more data is received than connection window allows" inAssertAllStagesStopped new WaitingForRequestData {
        sendFrame(DataFrame(TheStreamId, endStream = false, ByteString("0" * 100000)))
        val (_, errorCode) = expectGOAWAY()
        errorCode shouldEqual ErrorCode.FLOW_CONTROL_ERROR
      }
      "fail if more data is received than stream-level window allows" inAssertAllStagesStopped new WaitingForRequestData {
        // trigger a connection-level WINDOW_UPDATE
        sendDATA(TheStreamId, endStream = false, ByteString("0000"))
        expectWindowUpdate()

        sendFrame(DataFrame(TheStreamId, endStream = false, ByteString("0" * 100000)))
        expectRST_STREAM(TheStreamId, ErrorCode.FLOW_CONTROL_ERROR)
      }
      "fail entity stream if advertised content-length doesn't match" in pending
    }

    "support stream support for sending response entity data" should {
      abstract class WaitingForResponseSetup extends TestSetup with RequestResponseProbes with Http2FrameHpackSupport {
        val TheStreamId = 1
        val theRequest = HttpRequest(protocol = HttpProtocols.`HTTP/2.0`)
        sendRequest(TheStreamId, theRequest)
        expectRequest() shouldBe theRequest
      }
      abstract class WaitingForResponseDataSetup extends WaitingForResponseSetup {
        val entityDataOut = TestPublisher.probe[ByteString]()

        val response = HttpResponse(entity = HttpEntity(ContentTypes.`application/octet-stream`, Source.fromPublisher(entityDataOut)))
        emitResponse(TheStreamId, response)
        expectDecodedHEADERS(streamId = TheStreamId, endStream = false) shouldBe response.withEntity(HttpEntity.Empty.withContentType(ContentTypes.`application/octet-stream`))
      }

      "encode Content-Length and Content-Type headers" inAssertAllStagesStopped new WaitingForResponseSetup {
        val response = HttpResponse(entity = HttpEntity(ContentTypes.`application/octet-stream`, ByteString("abcde")))
        emitResponse(TheStreamId, response)
        val pairs = expectDecodedResponseHEADERSPairs(streamId = TheStreamId, endStream = false).toMap
        pairs should contain(":status" -> "200")
        pairs should contain("content-length" -> "5")
        pairs should contain("content-type" -> "application/octet-stream")
      }

      "send entity data as data frames" inAssertAllStagesStopped new WaitingForResponseDataSetup {
        val data1 = ByteString("abcd")
        entityDataOut.sendNext(data1)
        expectDATA(TheStreamId, endStream = false, data1)

        val data2 = ByteString("efghij")
        entityDataOut.sendNext(data2)
        expectDATA(TheStreamId, endStream = false, data2)

        entityDataOut.sendComplete()
        expectDATA(TheStreamId, endStream = true, ByteString.empty)
      }

      "parse priority frames" inAssertAllStagesStopped new WaitingForResponseDataSetup {
        sendPRIORITY(TheStreamId, true, 0, 5)
        entityDataOut.sendComplete()
        expectDATA(TheStreamId, endStream = true, ByteString.empty)
      }

      "cancel entity data source when peer sends RST_STREAM" inAssertAllStagesStopped new WaitingForResponseDataSetup {
        val data1 = ByteString("abcd")
        entityDataOut.sendNext(data1)
        expectDATA(TheStreamId, endStream = false, data1)

        sendRST_STREAM(TheStreamId, ErrorCode.CANCEL)
        entityDataOut.expectCancellation()
      }

      "handle RST_STREAM while data is waiting in outgoing stream buffer" inAssertAllStagesStopped new WaitingForResponseDataSetup {
        val data1 = ByteString("abcd")
        entityDataOut.sendNext(data1)

        sendRST_STREAM(TheStreamId, ErrorCode.CANCEL)

        entityDataOut.expectCancellation()
        toNet.expectNoBytes(100.millis) // the whole stage failed with bug #2236
      }

      "handle WINDOW_UPDATE correctly when received before started sending out response and while receiving request data" inAssertAllStagesStopped new WaitingForRequestData {
        val bytesToSend = 70000 // > Http2Protocol.InitialWindowSize
        val missingWindow = bytesToSend - Http2Protocol.InitialWindowSize
        require(missingWindow >= 0)
        // add missing window space immediately to both connection- and stream-level window
        sendWINDOW_UPDATE(0, missingWindow)
        sendWINDOW_UPDATE(TheStreamId, missingWindow)

        val entityDataOut = TestPublisher.probe[ByteString]()

        val response = HttpResponse(entity = HttpEntity(ContentTypes.`application/octet-stream`, Source.fromPublisher(entityDataOut)))
        emitResponse(TheStreamId, response)
        expectDecodedHEADERS(streamId = TheStreamId, endStream = false) shouldBe response.withEntity(HttpEntity.Empty.withContentType(ContentTypes.`application/octet-stream`))
        entityDataOut.sendNext(bytes(bytesToSend, 0x23))

        expectDATA(TheStreamId, false, bytesToSend)

        entityDataOut.sendComplete()
        expectDATA(TheStreamId, true, 0)
      }

      "handle WINDOW_UPDATE correctly when received before started sending out response" inAssertAllStagesStopped new WaitingForResponseSetup {
        val bytesToSend = 70000 // > Http2Protocol.InitialWindowSize
        val missingWindow = bytesToSend - Http2Protocol.InitialWindowSize
        require(missingWindow >= 0)
        // add missing window space immediately to both connection- and stream-level window
        sendWINDOW_UPDATE(0, missingWindow)
        sendWINDOW_UPDATE(TheStreamId, missingWindow)

        val entityDataOut = TestPublisher.probe[ByteString]()

        val response = HttpResponse(entity = HttpEntity(ContentTypes.`application/octet-stream`, Source.fromPublisher(entityDataOut)))
        emitResponse(TheStreamId, response)
        expectDecodedHEADERS(streamId = TheStreamId, endStream = false) shouldBe response.withEntity(HttpEntity.Empty.withContentType(ContentTypes.`application/octet-stream`))
        entityDataOut.sendNext(bytes(bytesToSend, 0x23))

        expectDATA(TheStreamId, false, bytesToSend)

        entityDataOut.sendComplete()
        expectDATA(TheStreamId, true, 0)
      }

      "distribute increases to SETTINGS_INITIAL_WINDOW_SIZE to streams correctly while sending out response" inAssertAllStagesStopped new WaitingForResponseDataSetup {
        // changes to SETTINGS_INITIAL_WINDOW_SIZE need to be distributed to active streams: https://httpwg.org/specs/rfc7540.html#InitialWindowSize
        val bytesToSend = 70000 // > Http2Protocol.InitialWindowSize
        val missingWindow = bytesToSend - Http2Protocol.InitialWindowSize
        require(missingWindow >= 0)
        // SETTINGS_INITIAL_WINDOW_SIZE only has ab effect on stream-level window, so we give the connection-level
        // window enough room immediately
        sendWINDOW_UPDATE(0, missingWindow)

        entityDataOut.sendNext(bytes(bytesToSend, 0x23))
        expectDATA(TheStreamId, false, Http2Protocol.InitialWindowSize)
        expectNoBytes(100.millis)

        // now increase SETTINGS_INITIAL_WINDOW_SIZE so that all data fits into WINDOW
        sendSETTING(Http2Protocol.SettingIdentifier.SETTINGS_INITIAL_WINDOW_SIZE, bytesToSend)
        updateFromServerWindows(TheStreamId, _ + missingWindow) // test probe doesn't automatically update window
        expectDATA(TheStreamId, false, missingWindow)
        expectSettingsAck() // FIXME: bug: we must send ACK before making use of the new setting, see https://github.com/akka/akka-http/issues/3553

        entityDataOut.sendComplete()
        expectDATA(TheStreamId, true, 0)
      }

      "handle RST_STREAM while waiting for a window update" inAssertAllStagesStopped new WaitingForResponseDataSetup {
        entityDataOut.sendNext(bytes(70000, 0x23)) // 70000 > Http2Protocol.InitialWindowSize
        sendWINDOW_UPDATE(TheStreamId, 10000) // enough window for the stream but not for the window

        expectDATA(TheStreamId, false, Http2Protocol.InitialWindowSize)

        // enough stream-level WINDOW, but too little connection-level WINDOW
        expectNoBytes(100.millis)

        // now the demuxer is in the WaitingForConnectionWindow state, cancel the connection
        sendRST_STREAM(TheStreamId, ErrorCode.CANCEL)

        entityDataOut.expectCancellation()
        expectNoBytes(100.millis)

        // now increase connection-level window again and see if everything still works
        sendWINDOW_UPDATE(0, 10000)
        expectNoBytes(100.millis) // don't expect anything, stream has been cancelled in the meantime
      }

      "cancel entity data source when peer sends RST_STREAM before entity is subscribed" inAssertAllStagesStopped new TestSetup with RequestResponseProbes with Http2FrameHpackSupport {
        val theRequest = HttpRequest(protocol = HttpProtocols.`HTTP/2.0`)
        sendRequest(1, theRequest)
        expectRequest() shouldBe theRequest

        sendRST_STREAM(1, ErrorCode.CANCEL)

        val entityDataOut = TestPublisher.probe[ByteString]()
        val response = HttpResponse(entity = HttpEntity(ContentTypes.`application/octet-stream`, Source.fromPublisher(entityDataOut)))
        emitResponse(1, response)
        expectNoBytes(100.millis) // don't expect response on closed connection
        entityDataOut.expectCancellation()
      }

      "send RST_STREAM when entity data stream fails" inAssertAllStagesStopped new WaitingForResponseDataSetup {
        val data1 = ByteString("abcd")
        entityDataOut.sendNext(data1)
        expectDATA(TheStreamId, endStream = false, data1)

        class MyProblem extends RuntimeException

        EventFilter[MyProblem](pattern = "Substream 1 failed with .*", occurrences = 1).intercept {
          entityDataOut.sendError(new MyProblem)
          expectRST_STREAM(1, ErrorCode.INTERNAL_ERROR)
        }
      }
      "fail if advertised content-length doesn't match" in pending

      "send data frame with exactly the number of remaining connection-level window bytes even when chunk is bigger than that" inAssertAllStagesStopped new WaitingForResponseDataSetup {
        // otherwise, the stream may stall if the client doesn't send another window update (which it isn't required to do
        // unless a window falls to 0)

        entityDataOut.sendNext(bytes(70000, 0x23)) // 70000 > Http2Protocol.InitialWindowSize
        expectDATA(TheStreamId, false, Http2Protocol.InitialWindowSize)

        expectNoBytes(100.millis)

        sendWINDOW_UPDATE(TheStreamId, 10000) // > than the remaining bytes (70000 - InitialWindowSize)
        sendWINDOW_UPDATE(0, 10000)

        expectDATA(TheStreamId, false, 70000 - Http2Protocol.InitialWindowSize)
      }

      "backpressure response entity stream until WINDOW_UPDATE was received" inAssertAllStagesStopped new WaitingForResponseDataSetup {
        var totalSentBytes = 0
        var totalReceivedBytes = 0

        entityDataOut.ensureSubscription()

        def receiveData(maxBytes: Int = Int.MaxValue): Unit = {
          val (false, data) = expectDATAFrame(TheStreamId)
          totalReceivedBytes += data.size
        }

        def sendAWindow(): Unit = {
          val window = remainingFromServerWindowFor(TheStreamId)
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

        sendWINDOW_UPDATE(TheStreamId, totalSentBytes)
        sendWINDOW_UPDATE(0, totalSentBytes)

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
        sendFrame(PingFrame(ack = false, pingData))

        // now expect PING ack frame to "overtake" the data frame
        expectFrame(FrameType.PING, Flags.ACK, 0, pingData)
        expectDATA(TheStreamId, endStream = false, responseDataChunk)
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
        emitResponse(TheStreamId, response)
        expectDecodedHEADERS(streamId = TheStreamId, endStream = false)
        expectDATA(TheStreamId, endStream = false, ByteString("foobar"))
        expectDecodedHEADERS(streamId = TheStreamId).headers should be(immutable.Seq(RawHeader("status", "grpc-status 10")))
      }
      "include the trailing headers even when the buffer is emptied before sending the last chunk" inAssertAllStagesStopped new WaitingForResponseSetup {
        val queuePromise = Promise[SourceQueueWithComplete[HttpEntity.ChunkStreamPart]]()

        val response = HttpResponse(entity = HttpEntity.Chunked(
          ContentTypes.`application/octet-stream`,
          Source.queue[HttpEntity.ChunkStreamPart](100, OverflowStrategy.fail)
            .mapMaterializedValue(queuePromise.success(_))
        ))
        emitResponse(TheStreamId, response)
        val chunkQueue = Await.result(queuePromise.future, 10.seconds)

        chunkQueue.offer(HttpEntity.Chunk("foo"))
        chunkQueue.offer(HttpEntity.Chunk("bar"))

        expectDecodedHEADERS(streamId = TheStreamId, endStream = false)
        expectDATA(TheStreamId, endStream = false, ByteString("foobar"))

        chunkQueue.offer(HttpEntity.LastChunk(trailer = immutable.Seq[HttpHeader](RawHeader("Status", "grpc-status 10"))))
        chunkQueue.complete()
        expectDecodedHEADERS(streamId = TheStreamId).headers should be(immutable.Seq(RawHeader("status", "grpc-status 10")))
      }
      "send the trailing headers immediately, even when the stream window is depleted" inAssertAllStagesStopped new WaitingForResponseSetup {
        val queuePromise = Promise[SourceQueueWithComplete[HttpEntity.ChunkStreamPart]]()

        val response = HttpResponse(entity = HttpEntity.Chunked(
          ContentTypes.`application/octet-stream`,
          Source.queue[HttpEntity.ChunkStreamPart](100, OverflowStrategy.fail)
            .mapMaterializedValue(queuePromise.success(_))
        ))
        emitResponse(TheStreamId, response)

        val chunkQueue = Await.result(queuePromise.future, 10.seconds)

        def depleteWindow(): Unit = {
          val toSend: Int = remainingFromServerWindowFor(TheStreamId) min 1000
          if (toSend != 0) {
            val data = "x" * toSend
            Await.result(chunkQueue.offer(HttpEntity.Chunk(data)), 3.seconds)
            expectDATA(TheStreamId, endStream = false, ByteString(data))
            depleteWindow()
          }
        }

        expectDecodedHEADERS(streamId = TheStreamId, endStream = false)
        depleteWindow()

        chunkQueue.offer(HttpEntity.LastChunk(trailer = immutable.Seq[HttpHeader](RawHeader("grpc-status", "10"))))
        chunkQueue.complete()
        expectDecodedHEADERS(streamId = TheStreamId, endStream = true).headers should be(immutable.Seq(RawHeader("grpc-status", "10")))
      }
      "send the trailing headers even when last data chunk was delayed by window depletion" inAssertAllStagesStopped new WaitingForResponseSetup {
        val queuePromise = Promise[SourceQueueWithComplete[HttpEntity.ChunkStreamPart]]()

        val response = HttpResponse(entity = HttpEntity.Chunked(
          ContentTypes.`application/octet-stream`,
          Source.queue[HttpEntity.ChunkStreamPart](100, OverflowStrategy.fail)
            .mapMaterializedValue(queuePromise.success(_))
        ))
        emitResponse(TheStreamId, response)

        val chunkQueue = Await.result(queuePromise.future, 10.seconds)

        def depleteWindow(): Unit = {
          val toSend: Int = remainingFromServerWindowFor(TheStreamId) min 1000
          if (toSend != 0) {
            val data = "x" * toSend
            Await.result(chunkQueue.offer(HttpEntity.Chunk(data)), 3.seconds)
            expectDATA(TheStreamId, endStream = false, ByteString(data))
            depleteWindow()
          }
        }

        expectDecodedHEADERS(streamId = TheStreamId, endStream = false)
        depleteWindow()

        val lastData = ByteString("y" * 500)
        chunkQueue.offer(HttpEntity.Chunk(lastData)) // even out of connection window try to send one last chunk that will be buffered
        chunkQueue.offer(HttpEntity.LastChunk(trailer = immutable.Seq[HttpHeader](RawHeader("grpc-status", "10"))))
        chunkQueue.complete()

        toNet.request(1)
        // now increase windows somewhat but not over the buffered amount
        sendWINDOW_UPDATE(TheStreamId, 100)
        sendWINDOW_UPDATE(0, 100)
        expectDATA(TheStreamId, endStream = false, lastData.take(100))

        // now send the remaining data
        sendWINDOW_UPDATE(TheStreamId, 1000)
        sendWINDOW_UPDATE(0, 1000)
        expectDATA(TheStreamId, endStream = false, lastData.drop(100))

        expectDecodedHEADERS(streamId = TheStreamId, endStream = true).headers should be(immutable.Seq(RawHeader("grpc-status", "10")))
      }
    }

    "support multiple concurrent substreams" should {
      "receive two requests concurrently" inAssertAllStagesStopped new TestSetup with RequestResponseProbes with Http2FrameHpackSupport {
        val request1 =
          HttpRequest(
            protocol = HttpProtocols.`HTTP/2.0`,
            entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, ""))

        sendRequestHEADERS(1, request1, endStream = false)

        val gotRequest1 = expectRequest()
        gotRequest1.withEntity(HttpEntity.Empty) shouldBe request1.withEntity(HttpEntity.Empty)
        val request1EntityProbe = ByteStringSinkProbe()
        gotRequest1.entity.dataBytes.runWith(request1EntityProbe.sink)

        val request2 =
          HttpRequest(
            protocol = HttpProtocols.`HTTP/2.0`,
            entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, ""))

        sendRequestHEADERS(3, request2, endStream = false)

        val gotRequest2 = expectRequest()
        gotRequest2.withEntity(HttpEntity.Empty) shouldBe request2.withEntity(HttpEntity.Empty)
        val request2EntityProbe = ByteStringSinkProbe()
        gotRequest2.entity.dataBytes.runWith(request2EntityProbe.sink)

        sendDATA(3, endStream = false, ByteString("abc"))
        request2EntityProbe.expectUtf8EncodedString("abc")

        sendDATA(1, endStream = false, ByteString("def"))
        request1EntityProbe.expectUtf8EncodedString("def")

        // now fail stream 2
        //sendRST_STREAM(3, ErrorCode.INTERNAL_ERROR)
        //request2EntityProbe.expectError()

        // make sure that other stream is not affected
        sendDATA(1, endStream = true, ByteString("ghi"))
        request1EntityProbe.expectUtf8EncodedString("ghi")
        request1EntityProbe.expectComplete()
      }
      "send two responses concurrently" inAssertAllStagesStopped new TestSetup with RequestResponseProbes with Http2FrameHpackSupport {
        val theRequest = HttpRequest(protocol = HttpProtocols.`HTTP/2.0`)
        sendRequest(1, theRequest)
        expectRequest() shouldBe theRequest

        val entity1DataOut = TestPublisher.probe[ByteString]()
        val response1 = HttpResponse(entity = HttpEntity(ContentTypes.`application/octet-stream`, Source.fromPublisher(entity1DataOut)))
        emitResponse(1, response1)
        expectDecodedHEADERS(streamId = 1, endStream = false) shouldBe response1.withEntity(HttpEntity.Empty.withContentType(ContentTypes.`application/octet-stream`))

        def sendDataAndExpectOnNet(outStream: TestPublisher.Probe[ByteString], streamId: Int, dataString: String, endStream: Boolean = false): Unit = {
          val data = ByteString(dataString)
          if (dataString.nonEmpty) outStream.sendNext(data)
          if (endStream) outStream.sendComplete()
          if (data.nonEmpty || endStream) expectDATA(streamId, endStream = endStream, data)
        }

        sendDataAndExpectOnNet(entity1DataOut, 1, "abc")

        // send second request
        sendRequest(3, theRequest)
        expectRequest() shouldBe theRequest

        val entity2DataOut = TestPublisher.probe[ByteString]()
        val response2 = HttpResponse(entity = HttpEntity(ContentTypes.`application/octet-stream`, Source.fromPublisher(entity2DataOut)))
        emitResponse(3, response2)
        expectDecodedHEADERS(streamId = 3, endStream = false) shouldBe response2.withEntity(HttpEntity.Empty.withContentType(ContentTypes.`application/octet-stream`))

        // send again on stream 1
        sendDataAndExpectOnNet(entity1DataOut, 1, "zyx")

        // now send on stream 2
        sendDataAndExpectOnNet(entity2DataOut, 3, "mnopq")

        // now again on stream 1
        sendDataAndExpectOnNet(entity1DataOut, 1, "jklm")

        // send two data bits first but only pull and expect later
        entity1DataOut.sendNext(ByteString("hihihi"))
        entity2DataOut.sendNext(ByteString("hohoho"))
        expectDATA(1, endStream = false, ByteString("hihihi"))
        expectDATA(3, endStream = false, ByteString("hohoho"))

        // last data of stream 2
        sendDataAndExpectOnNet(entity2DataOut, 3, "uvwx", endStream = true)

        // also complete stream 1
        sendDataAndExpectOnNet(entity1DataOut, 1, "", endStream = true)
      }
      "close substreams when connection is shutting down" inAssertAllStagesStopped StreamTestKit.assertAllStagesStopped(new TestSetup with RequestResponseProbes with Http2FrameHpackSupport {
        val requestEntity = HttpEntity.Empty.withContentType(ContentTypes.`application/octet-stream`)
        val request = HttpRequest(entity = requestEntity)

        sendRequestHEADERS(1, request, false)
        val req = expectRequest()
        val reqProbe = ByteStringSinkProbe()
        req.entity.dataBytes.runWith(reqProbe.sink)

        val responseEntityProbe = TestPublisher.probe[ByteString]()
        val responseEntity = HttpEntity(ContentTypes.`application/octet-stream`, Source.fromPublisher(responseEntityProbe))
        val response = HttpResponse(200, entity = responseEntity)

        emitResponse(1, response)
        expectDecodedHEADERS(1, false)

        // check data flow for request entity
        sendDATA(1, false, ByteString("ping"))
        reqProbe.expectUtf8EncodedString("ping")

        // default settings will schedule a connection and a stream-level window update
        expectWindowUpdate()
        expectWindowUpdate()

        // check data flow for response entity
        responseEntityProbe.sendNext(ByteString("pong"))
        expectDATA(1, false, ByteString("pong"))

        fromNet.sendError(new RuntimeException("Connection broke"))

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

      "eventually send WINDOW_UPDATE frames for received data" in pending

      "reject WINDOW_UPDATE for connection with zero increment with PROTOCOL_ERROR" inAssertAllStagesStopped new TestSetup with RequestResponseProbes {
        sendWINDOW_UPDATE(0, 0) // illegal
        val (_, errorCode) = expectGOAWAY()

        errorCode should ===(ErrorCode.PROTOCOL_ERROR)
      }
      "reject WINDOW_UPDATE for stream with zero increment with PROTOCOL_ERROR" inAssertAllStagesStopped new TestSetup with RequestResponseProbes {
        // making sure we don't handle stream 0 and others differently here
        sendHEADERS(1, endStream = false, endHeaders = true, HPackSpecExamples.C41FirstRequestWithHuffman)
        sendWINDOW_UPDATE(1, 0) // illegal

        expectRST_STREAM(1, ErrorCode.PROTOCOL_ERROR)
      }

      "backpressure incoming frames when outgoing control frame buffer fills" inAssertAllStagesStopped new TestSetup with HandlerFunctionSupport {
        override def settings: ServerSettings = super.settings.mapHttp2Settings(_.withOutgoingControlFrameBufferSize(1))

        sendFrame(PingFrame(false, ByteString("abcdefgh")))
        // now one PING ack buffered
        // this one fills the input buffer between the probe and the server
        sendFrame(PingFrame(false, ByteString("abcdefgh")))

        // now there should be no new demand
        fromNet.pending shouldEqual 0
        fromNet.expectNoMessage(100.millis)
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
        sendFrame(FrameType.SETTINGS, ackFlag, 0, illegalPayload)

        val (_, error) = expectGOAWAY()
        error should ===(ErrorCode.FRAME_SIZE_ERROR)
      }
      "received SETTINGS frame with a length other than a multiple of 6 octets (invalid 6_5)" inAssertAllStagesStopped new TestSetup with RequestResponseProbes {
        val data = hex"00 00 02 04 00 00 00 00 00"

        sendFrame(FrameType.SETTINGS, ByteFlag.Zero, 0, data)

        val (_, error) = expectGOAWAY()
        error should ===(ErrorCode.FRAME_SIZE_ERROR)
      }

      "received SETTINGS_MAX_FRAME_SIZE should cause outgoing DATA to be chunked up into at-most-that-size parts " inAssertAllStagesStopped new TestSetup with RequestResponseProbes {
        val maxSize = Math.pow(2, 15).toInt // 32768, valid value (between 2^14 and 2^24 - 1)
        sendSETTING(SettingIdentifier.SETTINGS_MAX_FRAME_SIZE, maxSize)

        expectSettingsAck()

        sendWINDOW_UPDATE(0, maxSize * 10) // make sure we can receive such large response, on connection

        sendHEADERS(1, endStream = false, endHeaders = true, HPackSpecExamples.C41FirstRequestWithHuffman)
        sendWINDOW_UPDATE(1, maxSize * 5) // make sure we can receive such large response, on this stream

        val theTooLargeByteString = ByteString("x" * (maxSize * 2))
        val tooLargeEntity = Source.single(theTooLargeByteString)
        val tooLargeResponse = HttpResponse(entity = HttpEntity(ContentTypes.`application/octet-stream`, tooLargeEntity))
        emitResponse(1, tooLargeResponse)

        expectHeaderBlock(1, endStream = false)
        // we receive the DATA in 2 parts, since the ByteString does not fit in a single frame
        val d1 = expectDATA(1, endStream = false, numBytes = maxSize)
        val d2 = expectDATA(1, endStream = true, numBytes = maxSize)
        d1.toList should have length maxSize
        d2.toList should have length maxSize
        (d1 ++ d2) should ===(theTooLargeByteString) // makes sure we received the parts in the right order
      }

      "received SETTINGS_MAX_CONCURRENT_STREAMS should limit the number of streams" inAssertAllStagesStopped new TestSetup with RequestResponseProbes {
        sendSETTING(SettingIdentifier.SETTINGS_MAX_CONCURRENT_STREAMS, 1)
        expectSettingsAck()

        // TODO actually apply the limiting and verify it works
        // This test is not required until supporting PUSH_PROMISE.
      }

      "not limit response streams even when the client send a SETTINGS_MAX_CONCURRENT_STREAMS" inAssertAllStagesStopped new TestSetup(
        Setting(SettingIdentifier.SETTINGS_MAX_CONCURRENT_STREAMS, 1)
      ) with RequestResponseProbes {
        def openStream(streamId: Int) = sendHEADERS(streamId, endStream = false, endHeaders = true, HPackSpecExamples.C41FirstRequestWithHuffman)
        def closeStream(streamId: Int) = responseOut.sendNext(HPackSpecExamples.FirstResponse.addAttribute(Http2.streamId, streamId))

        // client set SETTINGS_MAX_CONCURRENT_STREAMS to 1 so an attempt from the server to open more streams
        // should fail. But as long as the outgoing streams are a result of client-initiated communication
        // they should succeed.
        openStream(1)
        openStream(3)
        openStream(5)
        openStream(7)

        expectRequest()
        expectRequest()
        expectRequest()
        expectRequest()
        // there are 4 in-flight requests
        expectNoBytes(100.millis)

        closeStream(1)
        closeStream(3)
        closeStream(5)
        closeStream(7)
        expect[HeadersFrame]().streamId shouldBe (1)
        expect[HeadersFrame]().streamId shouldBe (3)
        expect[HeadersFrame]().streamId shouldBe (5)
        expect[HeadersFrame]().streamId shouldBe (7)
      }

      "received SETTINGS_HEADER_TABLE_SIZE" in new TestSetup with RequestResponseProbes {
        sendSETTING(SettingIdentifier.SETTINGS_HEADER_TABLE_SIZE, Math.pow(2, 15).toInt) // 32768, valid value (between 2^14 and 2^24 - 1)

        expectSettingsAck() // TODO check that the setting was indeed applied
      }

      "react on invalid SETTINGS_INITIAL_WINDOW_SIZE with FLOW_CONTROL_ERROR" inAssertAllStagesStopped new TestSetup with RequestResponseProbes {
        // valid values are below 2^31 - 1 for int, which actually just means positive
        sendSETTING(SettingIdentifier.SETTINGS_INITIAL_WINDOW_SIZE, -1)

        val (_, code) = expectGOAWAY()
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
          sendHEADERS(streamId, endStream = false, endHeaders = true, requestHeaderBlock)
        )

        // while we don't exceed the limit, there's silence on the line
        expectNoBytes(100.millis)

        // When we exceed the limit, though...
        val lastValidStreamId = streamIds.max
        val firstInvalidStreamId = lastValidStreamId + 2
        sendHEADERS(firstInvalidStreamId, endStream = false, endHeaders = true, requestHeaderBlock)
        expectRST_STREAM(firstInvalidStreamId, ErrorCode.REFUSED_STREAM)
      }

      "reject new substreams when exceeding SETTINGS_MAX_CONCURRENT_STREAMS (with closed streams in between)" inAssertAllStagesStopped new TestSetup with RequestResponseProbes with Http2FrameHpackSupport {
        def maxStreams: Int = 32
        val requestHeaderBlock: ByteString = HPackSpecExamples.C41FirstRequestWithHuffman
        override def settings = super.settings.mapHttp2Settings(_.withMaxConcurrentStreams(maxStreams))

        // the Seq of stream ids has gaps
        // the skipped values should be represented as automatically-closed streams
        private val streamIds: IndexedSeq[Int] = (0 until maxStreams).map(id => 1 + id * 4)
        streamIds.foreach { streamId =>
          sendHEADERS(streamId, endStream = false, endHeaders = true, requestHeaderBlock)
        }

        // while we don't exceed the limit, there's silence on the line
        expectNoBytes(100.millis)

        // When we exceed the limit, though...
        val lastValidStreamId = streamIds.max
        val firstInvalidStreamId = lastValidStreamId + 2
        sendHEADERS(firstInvalidStreamId, endStream = false, endHeaders = true, requestHeaderBlock)
        expectRST_STREAM(firstInvalidStreamId, ErrorCode.REFUSED_STREAM)
      }

    }

    "support low-level features" should {
      "respond to PING frames (spec 6_7)" inAssertAllStagesStopped new TestSetup with RequestResponseProbes {
        sendFrame(FrameType.PING, new ByteFlag(0x0), 0, ByteString("data1234")) // ping frame data must be of size 8

        val (flag, payload) = expectFrameFlagsAndPayload(FrameType.PING, 0) // must be on stream 0
        flag should ===(new ByteFlag(0x1)) // a PING response
        payload should ===(ByteString("data1234"))
      }
      "NOT respond to PING ACK frames (spec 6_7)" inAssertAllStagesStopped new TestSetup with RequestResponseProbes {
        val AckFlag = new ByteFlag(0x1)
        sendFrame(FrameType.PING, AckFlag, 0, ByteString("data1234"))

        expectNoMessage(100.millis)
      }
      "respond to invalid (not 0x0 streamId) PING with GOAWAY PROTOCOL_ERROR (spec 6_7)" inAssertAllStagesStopped new TestSetup with RequestResponseProbes {
        val invalidIdForPing = 1
        sendFrame(FrameType.PING, ByteFlag.Zero, invalidIdForPing, ByteString("abcd1234"))

        val (_, errorCode) = expectGOAWAY()
        errorCode should ===(ErrorCode.PROTOCOL_ERROR)
      }
      "respond to invalid (length smaller than 8) with GOAWAY FRAME_SIZE_ERROR (spec 6_7)" inAssertAllStagesStopped new TestSetup with RequestResponseProbes {
        sendFrame(FrameType.PING, ByteFlag.Zero, 0x0, ByteString("abcd123"))

        val (_, errorCode) = expectGOAWAY()
        errorCode should ===(ErrorCode.FRAME_SIZE_ERROR)
      }
      "respond to invalid (length larger than 8) with GOAWAY FRAME_SIZE_ERROR (spec 6_7)" inAssertAllStagesStopped new TestSetup with RequestResponseProbes {
        sendFrame(FrameType.PING, ByteFlag.Zero, 0x0, ByteString("abcd12345"))

        val (_, errorCode) = expectGOAWAY()
        errorCode should ===(ErrorCode.FRAME_SIZE_ERROR)
      }
      "respond to PING frames giving precedence over any other kind pending frame" in pending
      "acknowledge SETTINGS frames" in pending
    }

    "respect the substream state machine" should {
      abstract class SimpleRequestResponseRoundtripSetup extends TestSetup with RequestResponseProbes

      "reject other frame than HEADERS/PUSH_PROMISE in idle state with connection-level PROTOCOL_ERROR (5.1)" inAssertAllStagesStopped new SimpleRequestResponseRoundtripSetup {
        sendDATA(9, endStream = true, HPackSpecExamples.C41FirstRequestWithHuffman)
        expectGOAWAY()
      }
      "reject incoming frames on already half-closed substream" in pending

      "reject even-numbered client-initiated substreams" inPendingUntilFixed new SimpleRequestResponseRoundtripSetup {
        sendHEADERS(2, endStream = true, endHeaders = true, HPackSpecExamples.C41FirstRequestWithHuffman)
        expectGOAWAY()
        // after GOAWAY we expect graceful completion after x amount of time
        // TODO: completion logic, wait?!
        expectGracefulCompletion()
      }

      "reject all other frames while waiting for CONTINUATION frames" in pending

      "accept trailing request HEADERS" inAssertAllStagesStopped new SimpleRequestResponseRoundtripSetup with Http2FrameHpackSupport {
        sendHEADERS(1, endStream = false, endHeaders = true, HPackSpecExamples.C41FirstRequestWithHuffman)
        sendHEADERS(1, endStream = true, Seq(RawHeader("grpc-status", "0")))

        // On the server side we read the entity as bytes, so the trailing headers are not available.
        expectRequest()
      }

      "reject HEADERS for already closed streams" inAssertAllStagesStopped new SimpleRequestResponseRoundtripSetup {
        sendHEADERS(1, endStream = true, endHeaders = true, HPackSpecExamples.C41FirstRequestWithHuffman)
        sendHEADERS(1, endStream = true, endHeaders = true, HPackSpecExamples.C41FirstRequestWithHuffman)
        expectGOAWAY()
      }

      "reject mid-stream HEADERS with endStream = false" inAssertAllStagesStopped new SimpleRequestResponseRoundtripSetup {
        sendHEADERS(1, endStream = false, endHeaders = true, HPackSpecExamples.C41FirstRequestWithHuffman)
        sendHEADERS(1, endStream = false, endHeaders = true, HPackSpecExamples.C41FirstRequestWithHuffman)
        expectGOAWAY()
      }

      "reject substream creation for streams invalidated by skipped substream IDs" inAssertAllStagesStopped new SimpleRequestResponseRoundtripSetup {
        sendHEADERS(9, endStream = true, endHeaders = true, HPackSpecExamples.C41FirstRequestWithHuffman)
        sendHEADERS(1, endStream = true, endHeaders = true, HPackSpecExamples.C41FirstRequestWithHuffman)
        expectGOAWAY()
      }
    }

    "expose synthetic headers" should {
      "expose Remote-Address" inAssertAllStagesStopped new TestSetup with RequestResponseProbes with Http2FrameHpackSupport {

        lazy val theAddress = "127.0.0.1"
        lazy val thePort = 1337
        override def modifyServer(server: BidiFlow[HttpResponse, ByteString, ByteString, HttpRequest, NotUsed]) =
          BidiFlow.fromGraph(server.withAttributes(
            HttpAttributes.remoteAddress(new InetSocketAddress(theAddress, thePort))
          ))

        val target = Uri("http://www.example.com/")
        sendRequest(1, HttpRequest(uri = target))
        requestIn.ensureSubscription()

        val request = expectRequestRaw()
        @silent("deprecated")
        val remoteAddressHeader = request.header[headers.`Remote-Address`].get
        remoteAddressHeader.address.getAddress.get().toString shouldBe ("/" + theAddress)
        remoteAddressHeader.address.getPort shouldBe thePort
      }

      "expose Tls-Session-Info" inAssertAllStagesStopped new TestSetup with RequestResponseProbes with Http2FrameHpackSupport {
        override def settings: ServerSettings =
          super.settings.withParserSettings(super.settings.parserSettings.withIncludeTlsSessionInfoHeader(true))

        lazy val expectedSession = SSLContext.getDefault.createSSLEngine.getSession
        override def modifyServer(server: BidiFlow[HttpResponse, ByteString, ByteString, HttpRequest, NotUsed]) =
          BidiFlow.fromGraph(server.withAttributes(
            HttpAttributes.tlsSessionInfo(expectedSession)
          ))

        val target = Uri("http://www.example.com/")
        sendRequest(1, HttpRequest(uri = target))
        requestIn.ensureSubscription()

        val request = expectRequestRaw()
        val tlsSessionInfoHeader = request.header[headers.`Tls-Session-Info`].get
        tlsSessionInfoHeader.session shouldBe expectedSession
      }
    }

    "must not swallow errors / warnings" in pending
  }

  implicit class InWithStoppedStages(name: String) {
    def inAssertAllStagesStopped(runTest: => TestSetup) =
      name in StreamTestKit.assertAllStagesStopped {
        val setup = runTest

        // force connection to shutdown (in case it is an invalid state)
        setup.fromNet.sendError(new RuntimeException)
        setup.toNet.cancel()

        // and then assert that all stages, substreams in particular, are stopped
      }
  }

  protected /* To make ByteFlag warnings go away */ abstract class TestSetupWithoutHandshake extends Http2FrameProbeDelegator with Http2FrameSending {
    implicit def ec = system.dispatcher

    val framesOut: Http2FrameProbe = Http2FrameProbe()
    override def frameProbeDelegate: Http2FrameProbe = framesOut
    val toNet = framesOut.plainDataProbe
    val fromNet = TestPublisher.probe[ByteString]()

    def handlerFlow: Flow[HttpRequest, HttpResponse, NotUsed]

    // hook to modify server, for example add attributes
    def modifyServer(server: BidiFlow[HttpResponse, ByteString, ByteString, HttpRequest, NotUsed]) = server

    // hook to modify server settings
    def settings: ServerSettings = ServerSettings(system).withServerHeader(None)

    final def theServer: BidiFlow[HttpResponse, ByteString, ByteString, HttpRequest, NotUsed] =
      modifyServer(Http2Blueprint.serverStack(settings, system.log))
        .atop(LogByteStringTools.logByteStringBidi("network-plain-text").addAttributes(Attributes(LogLevels(Logging.DebugLevel, Logging.DebugLevel, Logging.DebugLevel))))

    handlerFlow
      .join(theServer)
      .join(Flow.fromSinkAndSource(toNet.sink, Source.fromPublisher(fromNet)))
      .withAttributes(Attributes.inputBuffer(1, 1))
      .run()

    def sendBytes(bytes: ByteString): Unit = fromNet.sendNext(bytes)

    override def sendDATA(streamId: Int, endStream: Boolean, data: ByteString): Unit = {
      updateToServerWindowForConnection(_ - data.length)
      updateToServerWindows(streamId, _ - data.length)
      super.sendDATA(streamId, endStream, data)
    }

    override def sendWINDOW_UPDATE(streamId: Int, windowSizeIncrement: Int): Unit = {
      super.sendWINDOW_UPDATE(streamId, windowSizeIncrement)
      if (streamId == 0) updateFromServerWindowForConnection(_ + windowSizeIncrement)
      else updateFromServerWindows(streamId, _ + windowSizeIncrement)
    }

    def expectWindowUpdate(): Unit =
      framesOut.expectFrameFlagsStreamIdAndPayload(FrameType.WINDOW_UPDATE) match {
        case (flags, streamId, payload) =>
          // TODO: DRY up with autoFrameHandler
          val windowSizeIncrement = new ByteReader(payload).readIntBE()

          if (streamId == 0) updateToServerWindowForConnection(_ + windowSizeIncrement)
          else updateToServerWindows(streamId, _ + windowSizeIncrement)
      }

    final def pollForWindowUpdates(duration: FiniteDuration): Unit =
      try {
        toNet.within(duration)(expectWindowUpdate())

        pollForWindowUpdates(duration)
      } catch {
        case e: AssertionError if e.getMessage contains "but only got [0] bytes" =>
        // timeout, that's expected
        case e: AssertionError if (e.getMessage contains "block took") && (e.getMessage contains "exceeding") =>
          // pause like GC, poll again just to be sure
          pollForWindowUpdates(duration)
      }

    // keep counters that are updated on outgoing sendDATA and incoming WINDOW_UPDATE frames
    private var toServerWindows: Map[Int, Int] = Map.empty.withDefaultValue(Http2Protocol.InitialWindowSize)
    private var toServerWindowForConnection = Http2Protocol.InitialWindowSize
    def remainingToServerWindowForConnection: Int = toServerWindowForConnection
    def remainingToServerWindowFor(streamId: Int): Int = toServerWindows(streamId) min remainingToServerWindowForConnection

    def updateWindowMap(streamId: Int, update: Int => Int): Map[Int, Int] => Map[Int, Int] =
      map => map.updated(streamId, update(map(streamId)))

    def safeUpdate(update: Int => Int): Int => Int = { oldValue =>
      val newValue = update(oldValue)
      newValue should be >= 0
      newValue
    }

    def updateToServerWindows(streamId: Int, update: Int => Int): Unit =
      toServerWindows = updateWindowMap(streamId, safeUpdate(update))(toServerWindows)
    def updateToServerWindowForConnection(update: Int => Int): Unit =
      toServerWindowForConnection = safeUpdate(update)(toServerWindowForConnection)
  }

  /** Basic TestSetup that has already passed the exchange of the connection preface */
  abstract class TestSetup(initialClientSettings: Setting*) extends TestSetupWithoutHandshake {
    sendBytes(Http2Protocol.ClientConnectionPreface)
    expectSETTINGS()

    sendFrame(SettingsFrame(immutable.Seq.empty ++ initialClientSettings))
    expectSettingsAck()
  }

  /** Provides the user handler flow as `requestIn` and `responseOut` probes for manual stream interaction */
  trait RequestResponseProbes extends TestSetupWithoutHandshake {
    lazy val requestIn = TestSubscriber.probe[HttpRequest]()
    lazy val responseOut = TestPublisher.probe[HttpResponse]()

    def handlerFlow: Flow[HttpRequest, HttpResponse, NotUsed] =
      Flow.fromSinkAndSource(Sink.fromSubscriber(requestIn), Source.fromPublisher(responseOut))

    def expectRequest(): HttpRequest = requestIn.requestNext().removeAttribute(Http2.streamId)
    def expectRequestRaw(): HttpRequest = requestIn.requestNext() // TODO, make it so that internal headers are not listed in `headers` etc?
    def emitResponse(streamId: Int, response: HttpResponse): Unit =
      responseOut.sendNext(response.addAttribute(Http2.streamId, streamId))

    def expectGracefulCompletion(): Unit = {
      toNet.expectComplete()
      requestIn.expectComplete()
    }
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
