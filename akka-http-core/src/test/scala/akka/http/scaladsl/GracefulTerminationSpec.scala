/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ ArrayBlockingQueue, TimeUnit }

import akka.actor.ActorSystem
import akka.http.impl.util._
import akka.http.scaladsl.model.HttpEntity._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.Connection
import akka.http.scaladsl.settings.{ ConnectionPoolSettings, ServerSettings }
import akka.stream.scaladsl._
import akka.stream.{ Server => _, _ }
import akka.testkit._
import com.typesafe.config.{ Config, ConfigFactory }
import com.typesafe.sslconfig.akka.AkkaSSLConfig
import com.typesafe.sslconfig.ssl.{ SSLConfigSettings, SSLLooseConfig }
import org.scalactic.Tolerance
import org.scalatest.concurrent.{ Eventually, ScalaFutures }
import org.scalatest.{ Assertion, BeforeAndAfterAll, Matchers, WordSpec }

import scala.concurrent.duration._
import scala.concurrent.{ Await, Future, Promise }
import scala.util.Success

class GracefulTerminationSpec extends WordSpec with Matchers with BeforeAndAfterAll with ScalaFutures
  with WithLogCapturing
  with Tolerance with Eventually {
  val testConf: Config = ConfigFactory.parseString(
    """
    akka.loglevel = DEBUG
    akka.loggers = ["akka.http.impl.util.SilenceAllTestEventListener"]
    akka.stdout-loglevel = DEBUG
    windows-connection-abort-workaround-enabled = auto
    akka.http.server.request-timeout = infinite
    akka.http.server.log-unencrypted-network-bytes = 200
    akka.http.client.log-unencrypted-network-bytes = 200
                                                   """)
  implicit val system = ActorSystem(getClass.getSimpleName, testConf)
  implicit val materializer = ActorMaterializer()
  implicit val dispatcher = system.dispatcher

  implicit val patience = PatienceConfig(5.seconds.dilated, 200.millis)

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

      ensureServerDeliveredRequest()
      val terminateFuture = serverBinding.terminate(hardDeadline = time)

      r1.futureValue.status should ===(StatusCodes.ServiceUnavailable)

      Await.result(terminateFuture, 2.seconds)
      Await.result(serverBinding.whenTerminated, 2.seconds)
    }

    "provide whenTerminated future that completes once server has completed termination (existing connection, user reply)" in new TestSetup {
      val r1 = makeRequest() // establish connection
      val time: FiniteDuration = 3.seconds

      ensureServerDeliveredRequest() // we want the request to be in the server user's hands before we cause termination
      serverBinding.terminate(hardDeadline = time)
      reply(_ => HttpResponse(StatusCodes.OK))

      r1.futureValue.status should ===(StatusCodes.OK)

      Await.result(serverBinding.whenTerminated, 3.seconds)
    }
    "provide whenTerminated future that completes once server has completed termination (existing connection, user reply, terminate, no reply)" in new TestSetup {
      val r1 = makeRequest() // establish connection
      val time: FiniteDuration = 3.seconds

      ensureServerDeliveredRequest() // we want the request to be in the server user's hands before we cause termination
      serverBinding.terminate(hardDeadline = time)

      reply(_ => HttpResponse(StatusCodes.OK))
      r1.futureValue.status should ===(StatusCodes.OK)

      val r2 = makeRequest() // on the same connection
      // connections should be terminated, and no new requests should be accepted
      ensureConnectionIsClosed(r2)

      Await.result(serverBinding.whenTerminated, 3.seconds)
    }

    "in-flight request responses should include Connection: close and connection should be closed" in new TestSetup {
      val r1 = makeRequest() // establish connection
      val time: FiniteDuration = 3.seconds

      ensureServerDeliveredRequest() // we want the request to be in the server user's hands before we cause termination
      serverBinding.terminate(hardDeadline = time)
      Thread.sleep(time.toMillis / 2)
      reply(_ => HttpResponse(StatusCodes.OK))

      val response = r1.futureValue
      response.header[Connection] shouldBe Some(Connection("close"))
      response.status should ===(StatusCodes.OK)

      val r2 = makeRequest()
      ensureConnectionIsClosed(r2)

      Await.result(serverBinding.whenTerminated, 3.seconds)
    }

    "allow configuring the automatic termination response (in config)" in {
      new TestSetup {

        override def serverSettings: ServerSettings = {
          val c = ConfigFactory.parseString(
            """akka.http.server {
               termination-deadline-exceeded-response.status = 418 # I'm a teapot
             }""")
            .withFallback(system.settings.config)
          ServerSettings(c)
        }

        val r1 = makeRequest() // establish connection
        val time: FiniteDuration = 1.seconds

        ensureServerDeliveredRequest() // we want the request to be in the server user's hands before we cause termination
        serverBinding.terminate(hardDeadline = time)

        akka.pattern.after(2.second, system.scheduler) {
          Future.successful(reply(_ => HttpResponse(StatusCodes.OK)))
        }

        r1.futureValue.status should ===(StatusCodes.ImATeapot)

        Await.result(serverBinding.whenTerminated, 3.seconds)
      }
    }

    "allow configuring the automatic termination response (in code)" in {
      new TestSetup(Some(HttpResponse(status = StatusCodes.EnhanceYourCalm, entity = "Chill out, man!"))) {
        val r1 = makeRequest() // establish connection
        val time: FiniteDuration = 1.seconds

        ensureServerDeliveredRequest() // we want the request to be in the server user's hands before we cause termination
        serverBinding.terminate(hardDeadline = time)

        akka.pattern.after(2.second, system.scheduler) {
          Future.successful(reply(_ => HttpResponse(StatusCodes.OK)))
        }

        // the user handler will not receive this request and we will emit the 503 automatically
        r1.futureValue.status should ===(StatusCodes.EnhanceYourCalm) // the injected 503 response
        r1.futureValue.entity.toStrict(1.second).futureValue.data.utf8String should ===("Chill out, man!")

        Await.result(serverBinding.whenTerminated, 3.seconds)
      }
    }

  }

  private def ensureConnectionIsClosed(r: Future[HttpResponse]): Assertion =
    (the[StreamTcpException] thrownBy Await.result(r, 1.second)).getMessage should endWith("Connection refused")

  override def afterAll() = {
    TestKit.shutdownActorSystem(system)
  }

  class TestSetup(overrideResponse: Option[HttpResponse] = None) {
    val (hostname, port) = SocketUtil.temporaryServerHostnameAndPort()
    val counter = new AtomicInteger()
    var idleTimeoutBaseForUniqueness = 10

    def nextRequest = HttpRequest(uri = s"https://$hostname:$port/${counter.incrementAndGet()}", entity = "hello-from-client")

    val serverConnectionContext = ExampleHttpContexts.exampleServerContext
    // Disable hostname verification as ExampleHttpContexts.exampleClientContext sets hostname as akka.example.org
    val sslConfigSettings = SSLConfigSettings().withLoose(SSLLooseConfig().withDisableHostnameVerification(true))
    val sslConfig = AkkaSSLConfig().withSettings(sslConfigSettings)
    val clientConnectionContext = ConnectionContext.https(ExampleHttpContexts.exampleClientContext.sslContext, Some(sslConfig))

    val serverQueue = new ArrayBlockingQueue[(HttpRequest, Promise[HttpResponse])](16)

    def handler(req: HttpRequest): Future[HttpResponse] = {
      val p = Promise[HttpResponse]()
      val entry = req -> p
      serverQueue.add(entry)
      p.future
    }

    def ensureServerDeliveredRequest(): HttpRequest = {
      try eventually {
        // we're trying this until a request sent from client arrives in the "user handler" (in the queue)
        serverQueue.peek()._1
      } catch {
        case ex: Throwable => throw new Exception("Unable to ensure request arriving at server within time limit", ex)
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

    val routes: Flow[HttpRequest, HttpResponse, Any] = Flow[HttpRequest].mapAsync(1)(handler)
    val serverBinding =
      Http()
        .bindAndHandle(routes, hostname, port, connectionContext = serverConnectionContext, settings = serverSettings)
        .futureValue

    val basePoolSettings = ConnectionPoolSettings(system).withBaseConnectionBackoff(Duration.Zero)

    def makeRequest(ensureNewConnection: Boolean = false): Future[HttpResponse] = {
      if (ensureNewConnection) {
        // by changing the settings, we ensure we'll hit a new connection pool, which means it will be a new connection for sure.
        idleTimeoutBaseForUniqueness += 1
        val clientSettings = basePoolSettings.withIdleTimeout(idleTimeoutBaseForUniqueness.seconds)

        Http().singleRequest(nextRequest, connectionContext = clientConnectionContext, settings = clientSettings)
      } else {
        Http().singleRequest(nextRequest, connectionContext = clientConnectionContext, settings = basePoolSettings)
      }
    }
  }

}
