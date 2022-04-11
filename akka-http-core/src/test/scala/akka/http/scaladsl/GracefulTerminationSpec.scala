/*
 * Copyright (C) 2009-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ ArrayBlockingQueue, TimeUnit }
import akka.Done
import akka.actor.ActorSystem
import akka.http.impl.util._
import akka.http.scaladsl.model.HttpEntity._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{ Connection, HttpEncodings, `Content-Encoding` }
import akka.http.scaladsl.settings.ClientConnectionSettings
import akka.http.scaladsl.settings.{ ConnectionPoolSettings, ServerSettings }
import akka.stream.scaladsl._
import akka.stream.testkit.TestSubscriber.{ OnComplete, OnError }
import akka.stream.testkit.scaladsl.{ StreamTestKit, TestSink }
import akka.stream.{ Server => _, _ }
import akka.testkit._
import akka.util.ByteString
import org.scalactic.Tolerance
import org.scalatest.concurrent.Eventually

import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext, Future, Promise }
import scala.util.{ Failure, Success, Try }

class GracefulTerminationSpec
  extends AkkaSpecWithMaterializer("""
    windows-connection-abort-workaround-enabled = auto
    akka.http.server.request-timeout = infinite
    akka.http.server.log-unencrypted-network-bytes = 200
    akka.http.client.log-unencrypted-network-bytes = 200
                                                   """)
  with Tolerance with Eventually {
  implicit lazy val dispatcher: ExecutionContext = system.dispatcher

  implicit override val patience: PatienceConfig = PatienceConfig(5.seconds.dilated(system), 200.millis)

  "Unbinding" should {
    "not allow new connections" in new TestSetup {
      Await.result(serverBinding.unbind(), 1.second) should ===(Done)

      // immediately trying a new connection should cause `Connection refused` since we unbind immediately:
      val r = makeRequest(ensureNewConnection = true)
      val ex = intercept[StreamTcpException] {
        Await.result(r, 2.seconds)
      }
      ex.getMessage should include("Connection refused")
      serverBinding.terminate(hardDeadline = 2.seconds)
    }
  }

  "Graceful termination" should {

    "stop accepting new connections" in new TestSetup {
      val r1 = makeRequest()
      reply(_ => HttpResponse(entity = "reply"))

      r1.futureValue.entity should ===(HttpResponse(entity = "reply").entity)

      serverBinding.terminate(hardDeadline = 2.seconds)
      Thread.sleep(200)

      // immediately trying a new connection should cause `Connection refused` since we unbind immediately:
      val r3 = makeRequest(ensureNewConnection = true)
      val ex = intercept[StreamTcpException] {
        Await.result(r3, 2.seconds)
      }
      ex.getMessage should include("Connection refused")
    }

    "fail chunked response streams" in {
      // client system created on the outside, so we simulate that client is not shut down at the same time as server
      val clientSystem = ActorSystem("client")
      try {
        StreamTestKit.assertAllStagesStopped {
          new TestSetup {
            val r1 =
              Http()(clientSystem).singleRequest(nextRequest(), connectionContext = clientConnectionContext, settings = basePoolSettings)

            // reply with an infinite entity stream
            val chunks = Source
              .fromIterator(() => Iterator.from(1).map(v => ChunkStreamPart(s"reply$v,")))
              .throttle(1, 100.millis)
            reply(_ => HttpResponse(entity = HttpEntity.Chunked(ContentTypes.`text/plain(UTF-8)`, chunks)))

            // start reading the response
            val responseEntity = r1.futureValue.entity.dataBytes
              .via(Framing.delimiter(ByteString(","), 20))
              .runWith(TestSink.probe[ByteString])(SystemMaterializer(clientSystem).materializer)
            responseEntity.requestNext().utf8String should ===("reply1")

            val termination = serverBinding.terminate(hardDeadline = 1.second)
            // Right now graceful terminate will immediately kill the connection
            // even if a streamed response is still ongoing
            // FIXME: https://github.com/akka/akka-http/issues/3209
            eventually {
              responseEntity.expectEvent() shouldBe a[OnError]
            }
            termination.futureValue shouldBe Http.HttpServerTerminated
          }
        }
      } finally TestKit.shutdownActorSystem(clientSystem)
    }

    "fail close delimited response streams" ignore new TestSetup {
      val clientSystem = ActorSystem("client")
      val r1 =
        Http()(clientSystem).singleRequest(nextRequest(), connectionContext = clientConnectionContext, settings = basePoolSettings)

      // reply with an infinite entity stream
      val chunks = Source
        .fromIterator(() => Iterator.from(1).map(v => ByteString(s"reply$v,")))
        .throttle(1, 300.millis)
      reply(_ => HttpResponse(entity = HttpEntity.CloseDelimited(ContentTypes.`text/plain(UTF-8)`, chunks)))

      // start reading the response
      val response = r1.futureValue.entity.dataBytes
        .via(Framing.delimiter(ByteString(","), 20))
        .runWith(TestSink.probe[ByteString])
      response.requestNext().utf8String should ===("reply1")

      try {
        val termination = serverBinding.terminate(hardDeadline = 50.millis)
        response.request(20)
        // local testing shows the stream fails long after the 50 ms deadline
        response.expectNext().utf8String should ===("reply2")
        eventually {
          response.expectEvent() shouldBe a[OnError]
        }
        termination.futureValue shouldBe Http.HttpServerTerminated
      } finally {
        TestKit.shutdownActorSystem(clientSystem)
      }
    }

    "provide whenTerminated future that completes once server has completed termination (no connections)" in new TestSetup {
      val time: FiniteDuration = 2.seconds
      val deadline: Deadline = time.fromNow

      serverBinding.terminate(hardDeadline = time)
      serverBinding.whenTerminationSignalIssued.futureValue.time.toMillis shouldEqual (deadline.time.toMillis +- 500)

      // termination should kill all connections within the deadline and complete its whenTerminated by then as well
      // (we give it a second wiggle room)
      Await.result(serverBinding.whenTerminated, 3.seconds)
    }

    "provide whenTerminated future that completes once server has completed termination (existing connection, no user reply)" in new TestSetup {
      val r1 = makeRequest() // establish connection

      val time: FiniteDuration = 1.second

      ensureServerDeliveredRequest(r1)
      val terminateFuture = serverBinding.terminate(hardDeadline = time)

      Try(r1.futureValue) match {
        // either, the connection manages to get a 503 error code out
        case Success(r)  => r.status should ===(StatusCodes.ServiceUnavailable)
        // or, the connection was already torn down in which case we might get an error based on a retry
        case Failure(ex) => ex.getMessage.contains("Connection refused") shouldBe true
      }

      Await.result(terminateFuture, 2.seconds)
      Await.result(serverBinding.whenTerminated, 2.seconds)
    }

    "provide whenTerminated future that completes once server has completed termination (existing connection, user reply)" in new TestSetup {
      val r1 = makeRequest() // establish connection
      val time: FiniteDuration = 3.seconds

      ensureServerDeliveredRequest(r1) // we want the request to be in the server user's hands before we cause termination
      serverBinding.terminate(hardDeadline = time)
      // avoid race condition between termination and sending out response
      // FIXME: https://github.com/akka/akka-http/issues/4060
      Thread.sleep(500)
      reply(_ => HttpResponse(StatusCodes.OK))

      r1.futureValue.status should ===(StatusCodes.OK)

      Await.result(serverBinding.whenTerminated, 3.seconds)
    }
    "provide whenTerminated future that completes once server has completed termination (existing connection, user reply, terminate, no reply)" in new TestSetup {
      val r1 = makeRequest() // establish connection
      val time: FiniteDuration = 3.seconds

      ensureServerDeliveredRequest(r1) // we want the request to be in the server user's hands before we cause termination
      serverBinding.terminate(hardDeadline = time)
      // avoid race condition between termination and sending out response
      // FIXME: https://github.com/akka/akka-http/issues/4060
      Thread.sleep(500)
      reply(_ => HttpResponse(StatusCodes.OK))
      r1.futureValue.status should ===(StatusCodes.OK)

      val r2 = makeRequest() // on the same connection
      // connections should be terminated, and no new requests should be accepted
      ensureConnectionIsClosed(r2)

      Await.result(serverBinding.whenTerminated, 3.seconds)
    }

    "in-flight request responses should include additional Connection: close header and connection should be closed" in new TestSetup {
      override val basePoolSettings: ConnectionPoolSettings =
        super.basePoolSettings
          .withTransport(new ClientTransport {
            override def connectTo(host: String, port: Int, settings: ClientConnectionSettings)(implicit system: ActorSystem): Flow[ByteString, ByteString, Future[Http.OutgoingConnection]] = {
              ClientTransport.TCP.connectTo(serverBinding.localAddress.getHostName, serverBinding.localAddress.getPort, settings)
                .mapMaterializedValue { conn =>
                  val result = Promise[Http.OutgoingConnection]()
                  conn.onComplete {
                    case Success(s) => result.trySuccess(s)
                    case Failure(ex) =>
                      log.debug(s"Delaying failure ${ex.getMessage}")
                      system.scheduler.scheduleOnce(100.millis)(result.tryFailure(ex))
                  }
                  result.future
                }
            }
          })
          .withMaxRetries(0) // disable retries for this test since they will be extra slow because we delay failures each by 100ms

      val r1 = makeRequest() // establish connection
      val time: FiniteDuration = 3.seconds

      ensureServerDeliveredRequest(r1) // we want the request to be in the server user's hands before we cause termination
      serverBinding.terminate(hardDeadline = time)
      Thread.sleep(time.toMillis / 2)
      reply(_ => HttpResponse(StatusCodes.OK, List(Connection("keep-alive"), `Content-Encoding`(List(HttpEncodings.gzip)))))

      val response = r1.futureValue
      response.header[Connection] shouldBe Some(Connection("close"))
      response.header[`Content-Encoding`] shouldBe Some(`Content-Encoding`(List(HttpEncodings.gzip)))
      response.status should ===(StatusCodes.OK)

      val r2 = makeRequest()
      ensureConnectionIsClosed(r2)

      Await.result(serverBinding.whenTerminated, 3.seconds)
    }

    "allow configuring the automatic termination response (in config)" in {
      new TestSetup {

        override def serverSettings: ServerSettings =
          ServerSettings(
            """akka.http.server {
                 termination-deadline-exceeded-response.status = 418 # I'm a teapot
               }""")

        val r1 = makeRequest() // establish connection
        val time: FiniteDuration = 1.seconds

        ensureServerDeliveredRequest(r1) // we want the request to be in the server user's hands before we cause termination
        serverBinding.terminate(hardDeadline = time)

        akka.pattern.after(2.second, system.scheduler) {
          Future.successful(reply(_ => HttpResponse(StatusCodes.OK)))
        }

        Try(r1.futureValue) match {
          // either, the connection manages to send out the configured status
          case Success(r)  => r.status should ===(StatusCodes.ImATeapot) // the injected 503 response

          // or, the connection was already torn down in which case we might get an error based on a retry
          case Failure(ex) => ex.getMessage.contains("Connection refused") shouldBe true
        }

        Await.result(serverBinding.whenTerminated, 3.seconds)
      }
    }

    "allow configuring the automatic termination response (in code)" in {
      new TestSetup(Some(HttpResponse(status = StatusCodes.EnhanceYourCalm, entity = "Chill out, man!"))) {
        val r1 = makeRequest() // establish connection
        val time: FiniteDuration = 1.seconds

        ensureServerDeliveredRequest(r1) // we want the request to be in the server user's hands before we cause termination
        serverBinding.terminate(hardDeadline = time)

        akka.pattern.after(2.second, system.scheduler) {
          Future.successful(reply(_ => HttpResponse(StatusCodes.OK)))
        }

        // the user handler will not receive this request and we will emit the termination response
        Try(r1.futureValue) match {
          // either, the connection manages to send out the configured response
          case Success(r) =>
            r.status should ===(StatusCodes.EnhanceYourCalm) // the injected 503 response
            r.entity.toStrict(1.second).futureValue.data.utf8String should ===("Chill out, man!")

          // or, the connection was already torn down in which case we might get an error based on a retry
          case Failure(ex) => ex.getMessage.contains("Connection refused") shouldBe true
        }

        Await.result(serverBinding.whenTerminated, 3.seconds)
      }
    }

  }

  private def ensureConnectionIsClosed(r: Future[HttpResponse]): StreamTcpException =
    the[StreamTcpException] thrownBy Await.result(r, 1.second)

  class TestSetup(overrideResponse: Option[HttpResponse] = None) {
    val counter = new AtomicInteger()
    var idleTimeoutBaseForUniqueness = 10

    def nextRequest() = HttpRequest(uri = s"https://akka.example.org/${counter.incrementAndGet()}", entity = "hello-from-client")
    val serverConnectionContext = ExampleHttpContexts.exampleServerContext
    val clientConnectionContext = ExampleHttpContexts.exampleClientContext

    val serverQueue = new ArrayBlockingQueue[(HttpRequest, Promise[HttpResponse])](16)

    def handler(req: HttpRequest): Future[HttpResponse] = {
      val p = Promise[HttpResponse]()
      val entry = req -> p
      serverQueue.add(entry)
      p.future
    }

    def ensureServerDeliveredRequest(clientView: Future[HttpResponse]): HttpRequest = {
      try eventually {
        // we're trying this until a request sent from client arrives in the "user handler" (in the queue)
        serverQueue.peek()._1
      } catch {
        case ex: Throwable =>
          // If we didn't see the request at the server, perhaps it failed already at the client:
          clientView.value match {
            case Some(result) =>
              fail(s"Did not see request arrive at server, but client saw result [$result]")
            case _ =>
              fail("Unable to ensure request arriving at server within time limit", ex)
          }
      }
    }

    def reply(fn: HttpRequest => HttpResponse): Unit = {
      val popped = serverQueue.poll(2, TimeUnit.SECONDS)
      val (req, promise) = popped
      val res = fn(req.toStrict(1.second).futureValue)
      promise.complete(Success(res))
    }

    def serverSettings = {
      val s = settings.ServerSettings(system)
      overrideResponse match {
        case Some(response) => s.withTerminationDeadlineExceededResponse(response)
        case _              => s
      }
    }

    val handlerFlow: Flow[HttpRequest, HttpResponse, Any] = Flow[HttpRequest].mapAsync(1)(handler)
    val serverBinding =
      Http().newServerAt("localhost", 0).enableHttps(serverConnectionContext).withSettings(serverSettings).bindFlow(handlerFlow)
        .futureValue

    def basePoolSettings = ConnectionPoolSettings(system)
      .withBaseConnectionBackoff(Duration.Zero)
      .withTransport(ExampleHttpContexts.proxyTransport(serverBinding.localAddress))

    def makeRequest(ensureNewConnection: Boolean = false): Future[HttpResponse] = {
      if (ensureNewConnection) {
        // by changing the settings, we ensure we'll hit a new connection pool, which means it will be a new connection for sure.
        idleTimeoutBaseForUniqueness += 1
        val clientSettings = basePoolSettings.withIdleTimeout(idleTimeoutBaseForUniqueness.seconds)

        Http().singleRequest(nextRequest(), connectionContext = clientConnectionContext, settings = clientSettings)
      } else {
        Http().singleRequest(nextRequest(), connectionContext = clientConnectionContext, settings = basePoolSettings)
      }
    }
  }

}
