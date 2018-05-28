/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.server

import java.net.{ InetAddress, InetSocketAddress }

import akka.http.impl.engine.ws.ByteStringSinkProbe
import akka.http.impl.util._
import akka.http.scaladsl.Http.ServerLayer
import akka.http.scaladsl.model.HttpEntity._
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model.MediaTypes._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.settings.ServerSettings
import akka.stream.scaladsl._
import akka.stream.testkit.Utils.assertAllStagesStopped
import akka.stream.testkit._
import akka.stream.ActorMaterializer
import akka.testkit._
import akka.util.ByteString
import org.scalatest.Inside

import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.reflect.ClassTag
import scala.util.Random

class HttpServerSpec extends AkkaSpec(
  """akka.loggers = []
     akka.loglevel = OFF
     akka.http.server.request-timeout = infinite""") with Inside { spec ⇒
  implicit val materializer = ActorMaterializer()

  "The server implementation" should {
    "deliver an empty request as soon as all headers are received" in assertAllStagesStopped(new TestSetup {
      send("""GET / HTTP/1.1
             |Host: example.com
             |
             |""")

      expectRequest() mapHeaders (_.filterNot(_.is("timeout-access"))) shouldEqual HttpRequest(uri = "http://example.com/", headers = List(Host("example.com")))

      shutdownBlueprint()
    })

    "deliver a request as soon as all headers are received" in assertAllStagesStopped(new TestSetup {
      send("""POST / HTTP/1.1
             |Host: example.com
             |Content-Length: 12
             |
             |""")

      inside(expectRequest()) {
        case HttpRequest(POST, _, _, HttpEntity.Default(_, 12, data), _) ⇒
          val dataProbe = TestSubscriber.manualProbe[ByteString]
          data.to(Sink.fromSubscriber(dataProbe)).run()
          val sub = dataProbe.expectSubscription()
          sub.request(10)
          dataProbe.expectNoMessage(50.millis)

          send("abcdef")
          dataProbe.expectNext(ByteString("abcdef"))

          send("ghijk")
          dataProbe.expectNext(ByteString("ghijk"))
          dataProbe.expectNoMessage(50.millis)
      }

      shutdownBlueprint()
    })

    "deliver an error response as soon as a parsing error occurred" in assertAllStagesStopped(new TestSetup {
      send("""GET / HTTP/1.2
             |Host: example.com
             |
             |""")

      requests.request(1)

      expectResponseWithWipedDate(
        """HTTP/1.1 505 HTTP Version Not Supported
          |Server: akka-http/test
          |Date: XXXX
          |Connection: close
          |Content-Type: text/plain; charset=UTF-8
          |Content-Length: 74
          |
          |The server does not support the HTTP protocol version used in the request.""")

      netOut.expectComplete()
      netIn.sendComplete()
    })

    "report an invalid Chunked stream" in assertAllStagesStopped(new TestSetup {
      send("""POST / HTTP/1.1
             |Host: example.com
             |Transfer-Encoding: chunked
             |
             |6
             |abcdef
             |""")

      inside(expectRequest()) {
        case HttpRequest(POST, _, _, HttpEntity.Chunked(_, data), _) ⇒
          val dataProbe = TestSubscriber.manualProbe[ChunkStreamPart]
          data.to(Sink.fromSubscriber(dataProbe)).run()
          val sub = dataProbe.expectSubscription()
          sub.request(10)
          dataProbe.expectNext(Chunk(ByteString("abcdef")))
          dataProbe.expectNoMessage(50.millis)

          send("3ghi\r\n") // missing "\r\n" after the number of bytes
          val error = dataProbe.expectError()
          error.getMessage shouldEqual "Illegal character 'g' in chunk start"
          requests.expectComplete()

          responses.expectRequest()
          responses.sendError(error.asInstanceOf[Exception])

          expectResponseWithWipedDate(
            """HTTP/1.1 400 Bad Request
              |Server: akka-http/test
              |Date: XXXX
              |Connection: close
              |Content-Type: text/plain; charset=UTF-8
              |Content-Length: 36
              |
              |Illegal character 'g' in chunk start""")
      }

      netOut.expectComplete()
      netIn.sendComplete()
    })

    "deliver the request entity as it comes in strictly for an immediately completed Strict entity" in assertAllStagesStopped(new TestSetup {
      send("""POST /strict HTTP/1.1
             |Host: example.com
             |Content-Length: 12
             |
             |abcdefghijkl""")

      expectRequest() mapHeaders (_.filterNot(_.is("timeout-access"))) shouldEqual
        HttpRequest(
          method = POST,
          uri = "http://example.com/strict",
          headers = List(Host("example.com")),
          entity = HttpEntity.Strict(ContentTypes.`application/octet-stream`, ByteString("abcdefghijkl")))

      shutdownBlueprint()
    })

    "deliver the request entity as it comes in for a Default entity" in assertAllStagesStopped(new TestSetup {
      send("""POST / HTTP/1.1
             |Host: example.com
             |Content-Length: 12
             |
             |abcdef""")

      inside(expectRequest()) {
        case HttpRequest(POST, _, _, HttpEntity.Default(_, 12, data), _) ⇒
          val dataProbe = TestSubscriber.manualProbe[ByteString]
          data.to(Sink.fromSubscriber(dataProbe)).run()
          val sub = dataProbe.expectSubscription()
          sub.request(10)
          dataProbe.expectNext(ByteString("abcdef"))

          send("ghijk")
          dataProbe.expectNext(ByteString("ghijk"))
          dataProbe.expectNoMessage(50.millis)
      }

      shutdownBlueprint()
    })

    "deliver the request entity as it comes in for a chunked entity" in assertAllStagesStopped(new TestSetup {
      send("""POST / HTTP/1.1
             |Host: example.com
             |Transfer-Encoding: chunked
             |
             |6
             |abcdef
             |""")

      inside(expectRequest()) {
        case HttpRequest(POST, _, _, HttpEntity.Chunked(_, data), _) ⇒
          val dataProbe = TestSubscriber.manualProbe[ChunkStreamPart]
          data.to(Sink.fromSubscriber(dataProbe)).run()
          val sub = dataProbe.expectSubscription()
          sub.request(10)
          dataProbe.expectNext(Chunk(ByteString("abcdef")))

          send("3\r\nghi\r\n")
          dataProbe.expectNext(Chunk(ByteString("ghi")))
          dataProbe.expectNoMessage(50.millis)
      }
      shutdownBlueprint()
    })

    "deliver the second message properly after a Strict entity" in assertAllStagesStopped(new TestSetup {
      send("""POST /strict HTTP/1.1
             |Host: example.com
             |Content-Length: 12
             |
             |abcdefghijkl""")

      expectRequest() mapHeaders (_.filterNot(_.is("timeout-access"))) shouldEqual
        HttpRequest(
          method = POST,
          uri = "http://example.com/strict",
          headers = List(Host("example.com")),
          entity = HttpEntity.Strict(ContentTypes.`application/octet-stream`, ByteString("abcdefghijkl")))

      send("""POST /next-strict HTTP/1.1
             |Host: example.com
             |Content-Length: 12
             |
             |mnopqrstuvwx""")

      expectRequest() mapHeaders (_.filterNot(_.is("timeout-access"))) shouldEqual
        HttpRequest(
          method = POST,
          uri = "http://example.com/next-strict",
          headers = List(Host("example.com")),
          entity = HttpEntity.Strict(ContentTypes.`application/octet-stream`, ByteString("mnopqrstuvwx")))
      shutdownBlueprint()
    })

    "deliver the second message properly after a Default entity" in assertAllStagesStopped(new TestSetup {
      send("""POST / HTTP/1.1
             |Host: example.com
             |Content-Length: 12
             |
             |abcdef""")

      inside(expectRequest()) {
        case HttpRequest(POST, _, _, HttpEntity.Default(_, 12, data), _) ⇒
          val dataProbe = TestSubscriber.manualProbe[ByteString]
          data.to(Sink.fromSubscriber(dataProbe)).run()
          val sub = dataProbe.expectSubscription()
          sub.request(10)
          dataProbe.expectNext(ByteString("abcdef"))

          send("ghij")
          dataProbe.expectNext(ByteString("ghij"))

          send("kl")
          dataProbe.expectNext(ByteString("kl"))
          dataProbe.expectComplete()
      }

      send("""POST /next-strict HTTP/1.1
             |Host: example.com
             |Content-Length: 5
             |
             |abcde""")

      inside(expectRequest()) {
        case HttpRequest(POST, _, _, HttpEntity.Strict(_, data), _) ⇒
          data shouldEqual ByteString("abcde")
      }
      shutdownBlueprint()
    })

    "deliver the second message properly after a Chunked entity" in assertAllStagesStopped(new TestSetup {
      send("""POST /chunked HTTP/1.1
             |Host: example.com
             |Transfer-Encoding: chunked
             |
             |6
             |abcdef
             |""")

      inside(expectRequest()) {
        case HttpRequest(POST, _, _, HttpEntity.Chunked(_, data), _) ⇒
          val dataProbe = TestSubscriber.manualProbe[ChunkStreamPart]
          data.to(Sink.fromSubscriber(dataProbe)).run()
          val sub = dataProbe.expectSubscription()
          sub.request(10)
          dataProbe.expectNext(Chunk(ByteString("abcdef")))

          send("3\r\nghi\r\n")
          dataProbe.expectNext(ByteString("ghi"))
          dataProbe.expectNoMessage(50.millis)

          send("0\r\n\r\n")
          dataProbe.expectNext(LastChunk)
          dataProbe.expectComplete()
      }

      send("""POST /next-strict HTTP/1.1
             |Host: example.com
             |Content-Length: 5
             |
             |abcde""")

      inside(expectRequest()) {
        case HttpRequest(POST, _, _, HttpEntity.Strict(_, data), _) ⇒
          data shouldEqual ByteString("abcde")
      }
      shutdownBlueprint()
    })

    "close the request entity stream when the entity is complete for a Default entity" in assertAllStagesStopped(new TestSetup {
      send("""POST / HTTP/1.1
             |Host: example.com
             |Content-Length: 12
             |
             |abcdef""")

      inside(expectRequest()) {
        case HttpRequest(POST, _, _, HttpEntity.Default(_, 12, data), _) ⇒
          val dataProbe = TestSubscriber.manualProbe[ByteString]
          data.to(Sink.fromSubscriber(dataProbe)).run()
          val sub = dataProbe.expectSubscription()
          sub.request(10)
          dataProbe.expectNext(ByteString("abcdef"))

          send("ghijkl")
          dataProbe.expectNext(ByteString("ghijkl"))
          dataProbe.expectComplete()
      }
      shutdownBlueprint()
    })

    "close the request entity stream when the entity is complete for a Chunked entity" in assertAllStagesStopped(new TestSetup {
      send("""POST / HTTP/1.1
             |Host: example.com
             |Transfer-Encoding: chunked
             |
             |6
             |abcdef
             |""")

      inside(expectRequest()) {
        case HttpRequest(POST, _, _, HttpEntity.Chunked(_, data), _) ⇒
          val dataProbe = TestSubscriber.manualProbe[ChunkStreamPart]
          data.to(Sink.fromSubscriber(dataProbe)).run()
          val sub = dataProbe.expectSubscription()
          sub.request(10)
          dataProbe.expectNext(Chunk(ByteString("abcdef")))
          dataProbe.expectNoMessage(50.millis)

          send("0\r\n\r\n")
          dataProbe.expectNext(LastChunk)
          dataProbe.expectComplete()
      }
      shutdownBlueprint()
    })

    "close the connection if request entity stream has been cancelled" in assertAllStagesStopped(new TestSetup {
      // two chunks sent by client
      send("""POST / HTTP/1.1
             |Host: example.com
             |Transfer-Encoding: chunked
             |
             |6
             |abcdef
             |6
             |abcdef
             |0
             |
             |""")

      inside(expectRequest()) {
        case HttpRequest(POST, _, _, HttpEntity.Chunked(_, data), _) ⇒
          val dataProbe = TestSubscriber.manualProbe[ChunkStreamPart]
          // but only one consumed by server
          data.take(1).to(Sink.fromSubscriber(dataProbe)).run()
          val sub = dataProbe.expectSubscription()
          sub.request(1)
          dataProbe.expectNext(Chunk(ByteString("abcdef")))
          dataProbe.expectComplete()
          // connection closes once requested elements are consumed
          netIn.expectCancellation()
      }
      shutdownBlueprint()
    })

    "proceed to next request once previous request's entity has been drained" in assertAllStagesStopped(new TestSetup {
      def twice(action: ⇒ Unit): Unit = { action; action }

      twice {
        send("""POST / HTTP/1.1
               |Host: example.com
               |Transfer-Encoding: chunked
               |
               |6
               |abcdef
               |0
               |
               |""")

        val whenComplete = expectRequest().entity.dataBytes.runWith(Sink.ignore)
        whenComplete.futureValue should be(akka.Done)
      }
      shutdownBlueprint()
    })

    "report a truncated entity stream on the entity data stream and the main stream for a Default entity" in assertAllStagesStopped(new TestSetup {
      send("""POST / HTTP/1.1
             |Host: example.com
             |Content-Length: 12
             |
             |abcdef""")
      inside(expectRequest()) {
        case HttpRequest(POST, _, _, HttpEntity.Default(_, 12, data), _) ⇒
          val dataProbe = TestSubscriber.manualProbe[ByteString]
          data.to(Sink.fromSubscriber(dataProbe)).run()
          val sub = dataProbe.expectSubscription()
          sub.request(10)
          dataProbe.expectNext(ByteString("abcdef"))
          dataProbe.expectNoMessage(50.millis)
          closeNetworkInput()
          dataProbe.expectError().getMessage shouldEqual "Entity stream truncation"
      }
      shutdownBlueprint()
    })

    "report a truncated entity stream on the entity data stream and the main stream for a Chunked entity" in assertAllStagesStopped(new TestSetup {
      send("""POST / HTTP/1.1
             |Host: example.com
             |Transfer-Encoding: chunked
             |
             |6
             |abcdef
             |""")
      inside(expectRequest()) {
        case HttpRequest(POST, _, _, HttpEntity.Chunked(_, data), _) ⇒
          val dataProbe = TestSubscriber.manualProbe[ChunkStreamPart]
          data.to(Sink.fromSubscriber(dataProbe)).run()
          val sub = dataProbe.expectSubscription()
          sub.request(10)
          dataProbe.expectNext(Chunk(ByteString("abcdef")))
          dataProbe.expectNoMessage(50.millis)
          closeNetworkInput()
          dataProbe.expectError().getMessage shouldEqual "Entity stream truncation"
      }
      shutdownBlueprint()
    })

    "translate HEAD request to GET request when transparent-head-requests are enabled" in assertAllStagesStopped(new TestSetup {
      override def settings = ServerSettings(system).withTransparentHeadRequests(true)
      send("""HEAD / HTTP/1.1
             |Host: example.com
             |
             |""")
      expectRequest() mapHeaders (_.filterNot(_.is("timeout-access"))) shouldEqual HttpRequest(GET, uri = "http://example.com/", headers = List(Host("example.com")))
      shutdownBlueprint()
    })

    "keep HEAD request when transparent-head-requests are disabled" in assertAllStagesStopped(new TestSetup {
      override def settings = ServerSettings(system).withTransparentHeadRequests(false)
      send("""HEAD / HTTP/1.1
             |Host: example.com
             |
             |""")
      expectRequest() mapHeaders (_.filterNot(_.is("timeout-access"))) shouldEqual HttpRequest(HEAD, uri = "http://example.com/", headers = List(Host("example.com")))
      shutdownBlueprint()
    })

    "not emit entities when responding to HEAD requests if transparent-head-requests is enabled (with Strict)" in assertAllStagesStopped(new TestSetup {
      send("""HEAD / HTTP/1.1
             |Host: example.com
             |
             |""")
      inside(expectRequest()) {
        case HttpRequest(GET, _, _, _, _) ⇒
          responses.sendNext(HttpResponse(entity = HttpEntity.Strict(ContentTypes.`text/plain(UTF-8)`, ByteString("abcd"))))
          expectResponseWithWipedDate(
            """|HTTP/1.1 200 OK
               |Server: akka-http/test
               |Date: XXXX
               |Content-Type: text/plain; charset=UTF-8
               |Content-Length: 4
               |
               |""")
      }

      netIn.sendComplete()
      netOut.expectComplete()
    })

    "not emit entities when responding to HEAD requests if transparent-head-requests is enabled (with Default)" in assertAllStagesStopped(new TestSetup {
      send("""HEAD / HTTP/1.1
             |Host: example.com
             |
             |""")
      val data = TestPublisher.manualProbe[ByteString]()
      inside(expectRequest()) {
        case HttpRequest(GET, _, _, _, _) ⇒
          responses.sendNext(HttpResponse(entity = HttpEntity.Default(ContentTypes.`text/plain(UTF-8)`, 4, Source.fromPublisher(data))))
          val dataSub = data.expectSubscription()
          dataSub.expectCancellation()
          expectResponseWithWipedDate(
            """|HTTP/1.1 200 OK
               |Server: akka-http/test
               |Date: XXXX
               |Content-Type: text/plain; charset=UTF-8
               |Content-Length: 4
               |
               |""")
      }

      netIn.sendComplete()
      netOut.expectComplete()
    })

    "not emit entities when responding to HEAD requests if transparent-head-requests is enabled (with CloseDelimited)" in assertAllStagesStopped(new TestSetup {
      send("""HEAD / HTTP/1.1
             |Host: example.com
             |
             |""")
      val data = TestPublisher.manualProbe[ByteString]()
      inside(expectRequest()) {
        case HttpRequest(GET, _, _, _, _) ⇒
          responses.sendNext(HttpResponse(entity = HttpEntity.CloseDelimited(ContentTypes.`text/plain(UTF-8)`, Source.fromPublisher(data))))
          val dataSub = data.expectSubscription()
          dataSub.expectCancellation()
          expectResponseWithWipedDate(
            """|HTTP/1.1 200 OK
               |Server: akka-http/test
               |Date: XXXX
               |Content-Type: text/plain; charset=UTF-8
               |
               |""")
      }
      // No close should happen here since this was a HEAD request
      netOut.expectNoBytes(50.millis.dilated)

      netIn.sendComplete()
      netOut.expectComplete()
    })

    "not emit entities when responding to HEAD requests if transparent-head-requests is enabled (with Chunked)" in assertAllStagesStopped(new TestSetup {
      send("""HEAD / HTTP/1.1
             |Host: example.com
             |
             |""")
      val data = TestPublisher.manualProbe[ChunkStreamPart]()
      inside(expectRequest()) {
        case HttpRequest(GET, _, _, _, _) ⇒
          responses.sendNext(HttpResponse(entity = HttpEntity.Chunked(ContentTypes.`text/plain(UTF-8)`, Source.fromPublisher(data))))
          val dataSub = data.expectSubscription()
          dataSub.expectCancellation()
          expectResponseWithWipedDate(
            """|HTTP/1.1 200 OK
               |Server: akka-http/test
               |Date: XXXX
               |Transfer-Encoding: chunked
               |Content-Type: text/plain; charset=UTF-8
               |
               |""")
      }

      netIn.sendComplete()
      netOut.expectComplete()
    })

    "respect Connection headers of HEAD requests if transparent-head-requests is enabled" in assertAllStagesStopped(new TestSetup {
      send("""HEAD / HTTP/1.1
             |Host: example.com
             |Connection: close
             |
             |""")
      val data = TestPublisher.manualProbe[ByteString]()
      inside(expectRequest()) {
        case HttpRequest(GET, _, _, _, _) ⇒
          responses.sendNext(HttpResponse(entity = CloseDelimited(ContentTypes.`text/plain(UTF-8)`, Source.fromPublisher(data))))
          val dataSub = data.expectSubscription()
          dataSub.expectCancellation()
          netOut.expectBytes(1)
      }
      netOut.expectComplete()

      netIn.sendComplete()
    })

    "produce a `100 Continue` response when requested by a `Default` entity" in assertAllStagesStopped(new TestSetup {
      send("""POST / HTTP/1.1
             |Host: example.com
             |Expect: 100-continue
             |Content-Length: 16
             |
             |""")
      inside(expectRequest()) {
        case HttpRequest(POST, _, _, Default(ContentType(`application/octet-stream`, None), 16, data), _) ⇒
          val dataProbe = TestSubscriber.manualProbe[ByteString]
          data.to(Sink.fromSubscriber(dataProbe)).run()
          val dataSub = dataProbe.expectSubscription()
          netOut.expectNoBytes(50.millis.dilated)
          dataSub.request(1) // triggers `100 Continue` response
          expectResponseWithWipedDate(
            """HTTP/1.1 100 Continue
              |Server: akka-http/test
              |Date: XXXX
              |
              |""")
          dataProbe.expectNoMessage(50.millis)
          send("0123456789ABCDEF")
          dataProbe.expectNext(ByteString("0123456789ABCDEF"))
          dataSub.request(1)
          dataProbe.expectComplete()
          responses.sendNext(HttpResponse(entity = "Yeah"))
          expectResponseWithWipedDate(
            """HTTP/1.1 200 OK
              |Server: akka-http/test
              |Date: XXXX
              |Content-Type: text/plain; charset=UTF-8
              |Content-Length: 4
              |
              |Yeah""")
      }

      netIn.sendComplete()
      netOut.expectComplete()
    })

    "produce a `100 Continue` response when requested by a `Chunked` entity" in assertAllStagesStopped(new TestSetup {
      send("""POST / HTTP/1.1
             |Host: example.com
             |Expect: 100-continue
             |Transfer-Encoding: chunked
             |
             |""")
      inside(expectRequest()) {
        case HttpRequest(POST, _, _, Chunked(ContentType(`application/octet-stream`, None), data), _) ⇒
          val dataProbe = TestSubscriber.manualProbe[ChunkStreamPart]
          data.to(Sink.fromSubscriber(dataProbe)).run()
          val dataSub = dataProbe.expectSubscription()
          netOut.expectNoBytes(50.millis.dilated)
          dataSub.request(2) // triggers `100 Continue` response
          expectResponseWithWipedDate(
            """HTTP/1.1 100 Continue
              |Server: akka-http/test
              |Date: XXXX
              |
              |""")
          dataProbe.expectNoMessage(50.millis)
          send("""10
                 |0123456789ABCDEF
                 |0
                 |
                 |""")
          dataProbe.expectNext(Chunk(ByteString("0123456789ABCDEF")))
          dataProbe.expectNext(LastChunk)
          dataSub.request(1)
          dataProbe.expectComplete()
          responses.sendNext(HttpResponse(entity = "Yeah"))
          expectResponseWithWipedDate(
            """HTTP/1.1 200 OK
              |Server: akka-http/test
              |Date: XXXX
              |Content-Type: text/plain; charset=UTF-8
              |Content-Length: 4
              |
              |Yeah""")
      }

      netIn.sendComplete()
      netOut.expectComplete()
    })

    "render a closing response instead of `100 Continue` if request entity is not requested" in assertAllStagesStopped(new TestSetup {
      send(
        """POST / HTTP/1.1
             |Host: example.com
             |Expect: 100-continue
             |Content-Length: 16
             |
             |""")
      inside(expectRequest()) {
        case HttpRequest(POST, _, _, Default(ContentType(`application/octet-stream`, None), 16, data), _) ⇒
          responses.sendNext(HttpResponse(entity = "Yeah"))
          expectResponseWithWipedDate(
            """HTTP/1.1 200 OK
              |Server: akka-http/test
              |Date: XXXX
              |Connection: close
              |Content-Type: text/plain; charset=UTF-8
              |Content-Length: 4
              |
              |Yeah""")
      }

      // client then closes the connection
      netIn.sendComplete()
      requests.expectComplete()
      netOut.expectComplete()
    })

    "not fail with 'Cannot pull port (ControllerStage.requestParsingIn) twice' for early response to `100 Continue` request (after 100-Continue has been sent)" in assertAllStagesStopped(new TestSetup {
      send("""POST / HTTP/1.1
             |Host: example.com
             |Expect: 100-continue
             |Content-Length: 16
             |
             |""")
      val req = expectRequest()
      netOut.expectNoBytes(50.millis)
      val dataProbe = ByteStringSinkProbe()
      req.entity.dataBytes.to(dataProbe.sink).run()
      dataProbe.ensureSubscription()
      dataProbe.request(1) // trigger 100-Continue response

      expectResponseWithWipedDate(
        """HTTP/1.1 100 Continue
          |Server: akka-http/test
          |Date: XXXX
          |
          |""")

      val dataOutProbe = TestPublisher.probe[ByteString]()
      val outEntity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, 4, Source.fromPublisher(dataOutProbe))

      // send early response
      responses.sendNext(HttpResponse(entity = outEntity))
      expectResponseWithWipedDate(
        """HTTP/1.1 200 OK
          |Server: akka-http/test
          |Date: XXXX
          |Connection: close
          |Content-Type: text/plain; charset=UTF-8
          |Content-Length: 4
          |
          |""")

      // interleave sending of response with actual reception of request entity
      send("abc")
      dataProbe.expectUtf8EncodedString("abc")
      dataOutProbe.sendNext(ByteString("Yeah"))
      netOut.expectUtf8EncodedString("Yeah")
      dataOutProbe.sendComplete()

      netIn.sendComplete()
      netOut.expectComplete()
    })

    "not fail with 'Cannot pull port (ControllerStage.requestParsingIn) twice' for early response to `100 Continue` request (before 100-Continue has been sent)" in assertAllStagesStopped(new TestSetup {
      send("""POST / HTTP/1.1
             |Host: example.com
             |Expect: 100-continue
             |Content-Length: 16
             |
             |""")
      val req = expectRequest()

      val dataOutProbe = TestPublisher.probe[ByteString]()
      val outEntity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, 4, Source.fromPublisher(dataOutProbe))

      // send early response without waiting for 100 Continue to be sent (classical use case of 100 Continue in the first place)
      responses.sendNext(HttpResponse(entity = outEntity))
      expectResponseWithWipedDate(
        """HTTP/1.1 200 OK
          |Server: akka-http/test
          |Date: XXXX
          |Connection: close
          |Content-Type: text/plain; charset=UTF-8
          |Content-Length: 4
          |
          |""")

      // client chose to send data anyways (which is allowed by the spec)
      send("abc")
      val dataProbe = ByteStringSinkProbe()
      req.entity.dataBytes.to(dataProbe.sink).run()
      dataProbe.expectUtf8EncodedString("abc")

      // then finish response
      dataOutProbe.sendNext(ByteString("Yeah"))
      netOut.expectUtf8EncodedString("Yeah")
      dataOutProbe.sendComplete()

      netIn.sendComplete()
      netOut.expectComplete()
    })

    "render a 500 response on response stream errors from the application" in assertAllStagesStopped(new TestSetup {
      send("""GET / HTTP/1.1
             |Host: example.com
             |
             |""".stripMarginWithNewline("\r\n"))

      expectRequest() mapHeaders (_.filterNot(_.is("timeout-access"))) shouldEqual HttpRequest(uri = "http://example.com/", headers = List(Host("example.com")))

      responses.expectRequest()
      responses.sendError(new RuntimeException("CRASH BOOM BANG"))

      expectResponseWithWipedDate(
        """HTTP/1.1 500 Internal Server Error
          |Server: akka-http/test
          |Date: XXXX
          |Connection: close
          |Content-Length: 0
          |
          |""")

      netIn.sendComplete()
      netOut.expectComplete()
    })

    "correctly consume and render large requests and responses" in assertAllStagesStopped(new TestSetup {
      send("""POST / HTTP/1.1
             |Host: example.com
             |Content-Length: 100000
             |
             |""")

      val HttpRequest(POST, _, _, entity, _) = expectRequest()
      responses.sendNext(HttpResponse(entity = entity))

      expectResponseWithWipedDate(
        """HTTP/1.1 200 OK
          |Server: akka-http/test
          |Date: XXXX
          |Connection: close
          |Content-Type: application/octet-stream
          |Content-Length: 100000
          |
          |""")

      val random = new Random()
      @tailrec def rec(bytesLeft: Int): Unit =
        if (bytesLeft > 0) {
          val count = math.min(random.nextInt(1000) + 1, bytesLeft)
          val data = random.alphanumeric.take(count).mkString
          send(data)
          netOut.expectUtf8EncodedString(data)
          rec(bytesLeft - count)
        }
      rec(100000)

      netIn.sendComplete()
      responses.sendComplete()
      requests.request(1)
      requests.expectComplete()
      netOut.expectComplete()
    })

    "deliver a request with a non-RFC3986 request-target" in assertAllStagesStopped(new TestSetup {
      send("""GET //foo HTTP/1.1
             |Host: example.com
             |
             |""")

      expectRequest() mapHeaders (_.filterNot(_.is("timeout-access"))) shouldEqual HttpRequest(uri = "http://example.com//foo", headers = List(Host("example.com")))
      shutdownBlueprint()
    })

    "use default-host-header for HTTP/1.0 requests" in assertAllStagesStopped(new TestSetup {
      send("""GET /abc HTTP/1.0
             |
             |""")

      expectRequest() mapHeaders (_.filterNot(_.is("timeout-access"))) shouldEqual HttpRequest(uri = "http://example.com/abc", protocol = HttpProtocols.`HTTP/1.0`)

      override def settings: ServerSettings = super.settings.withDefaultHostHeader(Host("example.com"))

      shutdownBlueprint()
    })

    "fail an HTTP/1.0 request with 400 if no default-host-header is set" in assertAllStagesStopped(new TestSetup {
      send("""GET /abc HTTP/1.0
             |
             |""")

      requests.request(1)

      expectResponseWithWipedDate(
        """|HTTP/1.1 400 Bad Request
           |Server: akka-http/test
           |Date: XXXX
           |Connection: close
           |Content-Type: text/plain; charset=UTF-8
           |Content-Length: 110
           |
           |Cannot establish effective URI of request to `/abc`, request has a relative URI and is missing a `Host` header""")

      netIn.sendComplete()
      netOut.expectComplete()
    })

    "support remote-address-header when blueprint not constructed with it" in assertAllStagesStopped(new TestSetup {
      // coverage for #21130
      lazy val theAddress = InetAddress.getByName("127.5.2.1")

      override def settings: ServerSettings =
        super.settings.withRemoteAddressHeader(true)

      // this is the normal behavior for bindAndHandle(flow), it will set an attribute
      // with remote ip before flow is materialized, rather than from the blueprint apply method
      override def modifyServer(server: ServerLayer): ServerLayer = {
        BidiFlow.fromGraph(server.withAttributes(
          HttpAttributes.remoteAddress(new InetSocketAddress(theAddress, 8080))
        ))
      }

      send("""GET / HTTP/1.1
             |Host: example.com
             |
             |""".stripMarginWithNewline("\r\n"))

      val request = expectRequest()
      request.headers should contain(`Remote-Address`(RemoteAddress(theAddress, Some(8080))))

      shutdownBlueprint()
    })

    "don't leak stages when connection is closed for request" which {
      "uses GET method with an unread empty chunked entity" in assertAllStagesStopped(new TestSetup {
        send("""GET / HTTP/1.1
               |Host: example.com
               |Connection: close
               |Transfer-Encoding: chunked
               |
               |0
               |
               |""")

        expectRequest()
        // entity was not read

        // send out an 200 OK response
        responses.sendNext(HttpResponse())

        netIn.sendComplete()
        netOut.cancel()

        requests.expectComplete()
      })

      "uses GET request with an unread truncated chunked entity" in assertAllStagesStopped(new TestSetup {
        send("""GET / HTTP/1.1
               |Host: example.com
               |Connection: close
               |Transfer-Encoding: chunked
               |
               |0
               |""")

        expectRequest()
        // entity was not read

        // send out an 200 OK response
        responses.sendNext(HttpResponse())

        netIn.sendComplete()
        netOut.cancel()

        requests.expectComplete()
      })

      "uses GET request with a truncated default entity" in assertAllStagesStopped(new TestSetup {
        send("""GET / HTTP/1.1
               |Host: example.com
               |Content-Length: 1
               |
               |""")

        expectRequest()
        // entity was not read

        // send out an 200 OK response
        responses.sendNext(HttpResponse())

        netIn.sendComplete()
        netOut.cancel()

        requests.expectComplete()
      })
    }

    "support request timeouts" which {

      "are defined via the config" in assertAllStagesStopped(new RequestTimeoutTestSetup(10.millis) {
        send("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n")
        expectRequest().header[`Timeout-Access`] shouldBe defined
        expectResponseWithWipedDate(
          """HTTP/1.1 503 Service Unavailable
            |Server: akka-http/test
            |Date: XXXX
            |Content-Type: text/plain; charset=UTF-8
            |Content-Length: 105
            |
            |The server was not able to produce a timely response to your request.
            |Please try again in a short while!""")

        netIn.sendComplete()
        netOut.expectComplete()
      })

      "are programmatically increased (not expiring)" in assertAllStagesStopped(new RequestTimeoutTestSetup(50.millis) {
        send("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n")
        expectRequest().header[`Timeout-Access`].foreach(_.timeoutAccess.updateTimeout(250.millis.dilated))
        netOut.expectNoBytes(150.millis.dilated)
        responses.sendNext(HttpResponse())
        expectResponseWithWipedDate(
          """HTTP/1.1 200 OK
            |Server: akka-http/test
            |Date: XXXX
            |Content-Length: 0
            |
            |""")

        netIn.sendComplete()
        netOut.expectComplete()
      })

      "are programmatically increased (expiring)" in assertAllStagesStopped(new RequestTimeoutTestSetup(50.millis) {
        send("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n")
        expectRequest().header[`Timeout-Access`].foreach(_.timeoutAccess.updateTimeout(250.millis.dilated))
        netOut.expectNoBytes(150.millis.dilated)
        expectResponseWithWipedDate(
          """HTTP/1.1 503 Service Unavailable
            |Server: akka-http/test
            |Date: XXXX
            |Content-Type: text/plain; charset=UTF-8
            |Content-Length: 105
            |
            |The server was not able to produce a timely response to your request.
            |Please try again in a short while!""")

        netIn.sendComplete()
        netOut.expectComplete()
      })

      "are programmatically decreased" in assertAllStagesStopped(new RequestTimeoutTestSetup(250.millis) {
        send("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n")
        expectRequest().header[`Timeout-Access`].foreach(_.timeoutAccess.updateTimeout(50.millis.dilated))
        val mark = System.nanoTime()
        expectResponseWithWipedDate(
          """HTTP/1.1 503 Service Unavailable
            |Server: akka-http/test
            |Date: XXXX
            |Content-Type: text/plain; charset=UTF-8
            |Content-Length: 105
            |
            |The server was not able to produce a timely response to your request.
            |Please try again in a short while!""")
        (System.nanoTime() - mark) should be < (200 * 1000000L)

        netIn.sendComplete()
        netOut.expectComplete()
      })

      "have a programmatically set timeout handler" in assertAllStagesStopped(new RequestTimeoutTestSetup(400.millis) {
        send("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n")
        val timeoutResponse = HttpResponse(StatusCodes.InternalServerError, entity = "OOPS!")
        expectRequest().header[`Timeout-Access`].foreach(_.timeoutAccess.updateHandler(_ ⇒ timeoutResponse))
        expectResponseWithWipedDate(
          """HTTP/1.1 500 Internal Server Error
            |Server: akka-http/test
            |Date: XXXX
            |Content-Type: text/plain; charset=UTF-8
            |Content-Length: 5
            |
            |OOPS!""")

        netIn.sendComplete()
        netOut.expectComplete()
      })
    }

    "add `Connection: close` to early responses" in assertAllStagesStopped(new TestSetup {
      send("""POST / HTTP/1.1
             |Host: example.com
             |Content-Length: 100000
             |
             |""")

      val HttpRequest(POST, _, _, entity, _) = expectRequest()
      responses.sendNext(HttpResponse(status = StatusCodes.InsufficientStorage))
      entity.dataBytes.runWith(Sink.ignore)

      expectResponseWithWipedDate(
        """HTTP/1.1 507 Insufficient Storage
          |Server: akka-http/test
          |Date: XXXX
          |Connection: close
          |Content-Length: 0
          |
          |""")

      netIn.sendComplete()
      requests.expectComplete()
      netOut.expectComplete()
    })

    "support request length verification" which afterWord("is defined via") {

      class LengthVerificationTest(maxContentLength: Int) extends TestSetup(maxContentLength) {
        val entityBase = "0123456789ABCD"
        def sendStrictRequestWithLength(bytes: Int) =
          send(s"""POST /foo HTTP/1.1
                 |Host: example.com
                 |Content-Length: $bytes
                 |
                 |${entityBase take bytes}""")
        def sendDefaultRequestWithLength(bytes: Int) = {
          send(s"""POST /foo HTTP/1.1
                 |Host: example.com
                 |Content-Length: $bytes
                 |
                 |${entityBase take 3}""")
          send(entityBase.slice(3, 7))
          send(entityBase.slice(7, bytes))
        }
        def sendChunkedRequestWithLength(bytes: Int) =
          send(s"""POST /foo HTTP/1.1
                 |Host: example.com
                 |Transfer-Encoding: chunked
                 |
                 |3
                 |${entityBase take 3}
                 |4
                 |${entityBase.slice(3, 7)}
                 |${bytes - 7}
                 |${entityBase.slice(7, bytes)}
                 |0
                 |
                 |""")

        implicit class XRequest(request: HttpRequest) {
          def expectEntity[T <: HttpEntity: ClassTag](bytes: Int) =
            inside(request) {
              case HttpRequest(POST, _, _, entity: T, _) ⇒
                entity.toStrict(100.millis.dilated).awaitResult(100.millis.dilated).data.utf8String shouldEqual entityBase.take(bytes)
            }

          def expectDefaultEntityWithSizeError(limit: Int, actualSize: Int) =
            inside(request) {
              case HttpRequest(POST, _, _, entity @ HttpEntity.Default(_, `actualSize`, _), _) ⇒
                val error = the[Exception]
                  .thrownBy(entity.dataBytes.runFold(ByteString.empty)(_ ++ _).awaitResult(100.millis.dilated))
                  .getCause
                error shouldEqual EntityStreamSizeException(limit, Some(actualSize))
                error.getMessage should include("exceeded content length limit")

                responses.expectRequest()
                responses.sendError(error.asInstanceOf[Exception])

                expectResponseWithWipedDate(
                  s"""HTTP/1.1 413 Request Entity Too Large
                      |Server: akka-http/test
                      |Date: XXXX
                      |Connection: close
                      |Content-Type: text/plain; charset=UTF-8
                      |Content-Length: 75
                      |
                  |Request Content-Length of $actualSize bytes exceeds the configured limit of $limit bytes""")
            }

          def expectChunkedEntityWithSizeError(limit: Int) =
            inside(request) {
              case HttpRequest(POST, _, _, entity: HttpEntity.Chunked, _) ⇒
                val error = the[Exception]
                  .thrownBy(entity.dataBytes.runFold(ByteString.empty)(_ ++ _).awaitResult(100.millis.dilated))
                  .getCause
                error shouldEqual EntityStreamSizeException(limit, None)
                error.getMessage should include("exceeded content length limit")

                responses.expectRequest()
                responses.sendError(error.asInstanceOf[Exception])

                expectResponseWithWipedDate(
                  s"""HTTP/1.1 413 Request Entity Too Large
                    |Server: akka-http/test
                    |Date: XXXX
                    |Connection: close
                    |Content-Type: text/plain; charset=UTF-8
                    |Content-Length: 81
                    |
                    |Aggregated data length of request entity exceeds the configured limit of $limit bytes""")
            }
        }
      }

      "the config setting (strict entity)" in new LengthVerificationTest(maxContentLength = 10) {
        sendStrictRequestWithLength(10)
        expectRequest().expectEntity[HttpEntity.Strict](10)

        // entities that would be strict but have a Content-Length > the configured maximum are delivered
        // as single element Default entities!
        sendStrictRequestWithLength(11)
        expectRequest().expectDefaultEntityWithSizeError(limit = 10, actualSize = 11)
      }

      "the config setting (default entity)" in new LengthVerificationTest(maxContentLength = 10) {
        sendDefaultRequestWithLength(10)
        expectRequest().expectEntity[HttpEntity.Default](10)

        sendDefaultRequestWithLength(11)
        expectRequest().expectDefaultEntityWithSizeError(limit = 10, actualSize = 11)
      }

      "the config setting (chunked entity)" in new LengthVerificationTest(maxContentLength = 10) {
        sendChunkedRequestWithLength(10)
        expectRequest().expectEntity[HttpEntity.Chunked](10)

        sendChunkedRequestWithLength(11)
        expectRequest().expectChunkedEntityWithSizeError(limit = 10)
      }

      "a smaller programmatically-set limit (strict entity)" in new LengthVerificationTest(maxContentLength = 12) {
        sendStrictRequestWithLength(10)
        expectRequest().mapEntity(_ withSizeLimit 10).expectEntity[HttpEntity.Strict](10)

        // entities that would be strict but have a Content-Length > the configured maximum are delivered
        // as single element Default entities!
        sendStrictRequestWithLength(11)
        expectRequest().mapEntity(_ withSizeLimit 10).expectDefaultEntityWithSizeError(limit = 10, actualSize = 11)
      }

      "a smaller programmatically-set limit (default entity)" in new LengthVerificationTest(maxContentLength = 12) {
        sendDefaultRequestWithLength(10)
        expectRequest().mapEntity(_ withSizeLimit 10).expectEntity[HttpEntity.Default](10)

        sendDefaultRequestWithLength(11)
        expectRequest().mapEntity(_ withSizeLimit 10).expectDefaultEntityWithSizeError(limit = 10, actualSize = 11)
      }

      "a smaller programmatically-set limit (chunked entity)" in new LengthVerificationTest(maxContentLength = 12) {
        sendChunkedRequestWithLength(10)
        expectRequest().mapEntity(_ withSizeLimit 10).expectEntity[HttpEntity.Chunked](10)

        sendChunkedRequestWithLength(11)
        expectRequest().mapEntity(_ withSizeLimit 10).expectChunkedEntityWithSizeError(limit = 10)
      }

      "a larger programmatically-set limit (strict entity)" in new LengthVerificationTest(maxContentLength = 8) {
        // entities that would be strict but have a Content-Length > the configured maximum are delivered
        // as single element Default entities!
        sendStrictRequestWithLength(10)
        expectRequest().mapEntity(_ withSizeLimit 10).expectEntity[HttpEntity.Default](10)

        sendStrictRequestWithLength(11)
        expectRequest().mapEntity(_ withSizeLimit 10).expectDefaultEntityWithSizeError(limit = 10, actualSize = 11)
      }

      "a larger programmatically-set limit (default entity)" in new LengthVerificationTest(maxContentLength = 8) {
        sendDefaultRequestWithLength(10)
        expectRequest().mapEntity(_ withSizeLimit 10).expectEntity[HttpEntity.Default](10)

        sendDefaultRequestWithLength(11)
        expectRequest().mapEntity(_ withSizeLimit 10).expectDefaultEntityWithSizeError(limit = 10, actualSize = 11)
      }

      "a larger programmatically-set limit (chunked entity)" in new LengthVerificationTest(maxContentLength = 8) {
        sendChunkedRequestWithLength(10)
        expectRequest().mapEntity(_ withSizeLimit 10).expectEntity[HttpEntity.Chunked](10)

        sendChunkedRequestWithLength(11)
        expectRequest().mapEntity(_ withSizeLimit 10).expectChunkedEntityWithSizeError(limit = 10)
      }

      "the config setting applied before another attribute (default entity)" in new LengthVerificationTest(maxContentLength = 10) {
        def nameDataSource(name: String): RequestEntity ⇒ RequestEntity = {
          case x: HttpEntity.Default ⇒ x.copy(data = x.data named name)
          case _                     ⇒ ??? // prevent a compile-time warning
        }
        sendDefaultRequestWithLength(10)
        expectRequest().mapEntity(nameDataSource("foo")).expectEntity[HttpEntity.Default](10)

        sendDefaultRequestWithLength(11)
        expectRequest().mapEntity(nameDataSource("foo")).expectDefaultEntityWithSizeError(limit = 10, actualSize = 11)
      }
    }

    "reject CONNECT requests gracefully" in assertAllStagesStopped(new TestSetup {
      send("""CONNECT www.example.com:80 HTTP/1.1
             |Host: www.example.com:80
             |
             |""")

      requests.request(1)

      expectResponseWithWipedDate(
        """|HTTP/1.1 400 Bad Request
           |Server: akka-http/test
           |Date: XXXX
           |Connection: close
           |Content-Type: text/plain; charset=UTF-8
           |Content-Length: 34
           |
           |CONNECT requests are not supported""")

      netIn.sendComplete()
      netOut.expectComplete()
    })

    "reject requests with an invalid URI schema" in assertAllStagesStopped(new TestSetup {
      send("""GET htp://www.example.com:80 HTTP/1.1
             |Host: www.example.com:80
             |
             |""")

      requests.request(1)

      expectResponseWithWipedDate(
        """|HTTP/1.1 400 Bad Request
           |Server: akka-http/test
           |Date: XXXX
           |Connection: close
           |Content-Type: text/plain; charset=UTF-8
           |Content-Length: 64
           |
           |`uri` must have scheme "http", "https", "ws", "wss" or no scheme""")

      netIn.sendComplete()
      netOut.expectComplete()
    })

    "reject HTTP/1.1 requests with Host header that doesn't match absolute request target authority" in assertAllStagesStopped(new TestSetup {
      send("""GET http://www.example.com HTTP/1.1
             |Host: www.example.net
             |
             |""")

      requests.request(1)

      expectResponseWithWipedDate(
        """|HTTP/1.1 400 Bad Request
           |Server: akka-http/test
           |Date: XXXX
           |Connection: close
           |Content-Type: text/plain; charset=UTF-8
           |Content-Length: 97
           |
           |'Host' header value of request to `http://www.example.com` doesn't match request target authority""")

      netIn.sendComplete()
      netOut.expectComplete()
    })

  }
  class TestSetup(maxContentLength: Int = -1) extends HttpServerTestSetupBase {
    implicit def system = spec.system
    implicit def materializer = spec.materializer

    override def settings = {
      val s = super.settings
      if (maxContentLength < 0) s
      else s.withParserSettings(s.parserSettings.withMaxContentLength(maxContentLength))
    }
  }
  class RequestTimeoutTestSetup(requestTimeout: FiniteDuration) extends TestSetup {
    override def settings = {
      val s = super.settings
      s.withTimeouts(s.timeouts.withRequestTimeout(requestTimeout.dilated))
    }
  }
}
