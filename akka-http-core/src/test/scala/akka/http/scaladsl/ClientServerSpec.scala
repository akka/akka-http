/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl

import java.io.{ BufferedReader, BufferedWriter, InputStreamReader, OutputStreamWriter }
import java.net.{ BindException, Socket }
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future, Promise }
import scala.util.{ Success, Try }
import akka.actor.ActorSystem
import akka.http.impl.engine.ws.ByteStringSinkProbe
import akka.http.impl.util._
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.model.HttpEntity._
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{ Accept, Age, Date, Host, Server, `User-Agent` }
import akka.http.scaladsl.settings.{ ClientConnectionSettings, ConnectionPoolSettings, ServerSettings }
import akka.io.Tcp.SO
import akka.stream.scaladsl._
import akka.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler }
import akka.stream.testkit._
import akka.stream._
import akka.testkit._
import akka.util.ByteString
import com.typesafe.config.{ Config, ConfigFactory }
import com.typesafe.sslconfig.akka.AkkaSSLConfig
import com.typesafe.sslconfig.ssl.{ SSLConfigSettings, SSLLooseConfig }
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpec }
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.concurrent.Eventually.eventually

class ClientServerSpec extends WordSpec with Matchers with BeforeAndAfterAll with ScalaFutures {
  val testConf: Config = ConfigFactory.parseString("""
    akka.loggers = ["akka.testkit.TestEventListener"]
    akka.loglevel = ERROR
    akka.stdout-loglevel = ERROR
    windows-connection-abort-workaround-enabled = auto
    akka.log-dead-letters = OFF
    akka.http.server.request-timeout = infinite""")
  implicit val system = ActorSystem(getClass.getSimpleName, testConf)
  import system.dispatcher
  implicit val materializer = ActorMaterializer()
  implicit val patience = PatienceConfig(3.seconds.dilated)

  val testConf2: Config =
    ConfigFactory.parseString("akka.stream.materializer.subscription-timeout.timeout = 1 s")
      .withFallback(testConf)
  val system2 = ActorSystem(getClass.getSimpleName, testConf2)
  val materializer2 = ActorMaterializer.create(system2)

  "The low-level HTTP infrastructure" should {

    "properly bind a server" in {
      val (hostname, port) = SocketUtil.temporaryServerHostnameAndPort()
      val probe = TestSubscriber.manualProbe[Http.IncomingConnection]()
      val binding = Http().bind(hostname, port).toMat(Sink.fromSubscriber(probe))(Keep.left).run()
      val sub = probe.expectSubscription() // if we get it we are bound
      Await.result(binding, 1.second.dilated)
      sub.cancel()
    }

    "properly bind a server with a default port set via settings" in {
      val (hostname, port) = SocketUtil.temporaryServerHostnameAndPort()
      val probe = TestSubscriber.manualProbe[Http.IncomingConnection]()
      val settings = ServerSettings(system).withDefaultHttpPort(port)
      val binding = Http().bind(hostname, settings = settings).toMat(Sink.fromSubscriber(probe))(Keep.left).run()
      val sub = probe.expectSubscription() // if we get it we are bound
      val address = Await.result(binding, 1.second.dilated).localAddress
      address.getPort shouldEqual port
      sub.cancel()
    }

    "report failure if bind fails" in EventFilter[BindException](occurrences = 2).intercept {
      val (hostname, port) = SocketUtil.temporaryServerHostnameAndPort()
      val binding = Http().bind(hostname, port)
      val probe1 = TestSubscriber.manualProbe[Http.IncomingConnection]()
      // Bind succeeded, we have a local address
      val b1 = Await.result(binding.to(Sink.fromSubscriber(probe1)).run(), 3.seconds.dilated)
      probe1.expectSubscription()

      val probe2 = TestSubscriber.manualProbe[Http.IncomingConnection]()
      an[BindFailedException] shouldBe thrownBy { Await.result(binding.to(Sink.fromSubscriber(probe2)).run(), 3.seconds.dilated) }
      probe2.expectSubscriptionAndError()

      val probe3 = TestSubscriber.manualProbe[Http.IncomingConnection]()
      an[BindFailedException] shouldBe thrownBy { Await.result(binding.to(Sink.fromSubscriber(probe3)).run(), 3.seconds.dilated) }
      probe3.expectSubscriptionAndError()

      // Now unbind the first
      Await.result(b1.unbind(), 1.second.dilated)
      probe1.expectComplete()

      if (!akka.util.Helpers.isWindows) {
        val probe4 = TestSubscriber.manualProbe[Http.IncomingConnection]()
        // Bind succeeded, we have a local address
        val b2 = Await.result(binding.to(Sink.fromSubscriber(probe4)).run(), 3.seconds.dilated)
        probe4.expectSubscription()

        // clean up
        Await.result(b2.unbind(), 1.second.dilated)
      }
    }

    "properly terminate client when server is not running" in Utils.assertAllStagesStopped {
      for (i ← 1 to 10)
        withClue(s"iterator $i: ") {
          Source.single(HttpRequest(HttpMethods.POST, "/test", List.empty, HttpEntity(MediaTypes.`text/plain`.withCharset(HttpCharsets.`UTF-8`), "buh")))
            .via(Http(actorSystem).outgoingConnection("localhost", 7777))
            .runWith(Sink.head)
            .failed
            .futureValue shouldBe a[StreamTcpException]
        }
    }

    "run with bindAndHandleSync" in {
      val (hostname, port) = SocketUtil.temporaryServerHostnameAndPort()
      val binding = Http().bindAndHandleSync(_ ⇒ HttpResponse(), hostname, port)
      val b1 = Await.result(binding, 3.seconds.dilated)

      val (_, f) = Http().outgoingConnection(hostname, port)
        .runWith(Source.single(HttpRequest(uri = "/abc")), Sink.head)

      Await.result(f, 1.second.dilated)
      Await.result(b1.unbind(), 1.second.dilated)
    }

    "prevent more than the configured number of max-connections with bindAndHandle" in {
      val (hostname, port) = SocketUtil.temporaryServerHostnameAndPort()
      val settings = ServerSettings(system).withMaxConnections(1)

      val receivedSlow = Promise[Long]()
      val receivedFast = Promise[Long]()

      def handle(req: HttpRequest): Future[HttpResponse] = {
        req.uri.path.toString match {
          case "/slow" ⇒
            receivedSlow.complete(Success(System.nanoTime()))
            akka.pattern.after(1.seconds.dilated, system.scheduler)(Future.successful(HttpResponse()))
          case "/fast" ⇒
            receivedFast.complete(Success(System.nanoTime()))
            Future.successful(HttpResponse())
        }
      }

      val binding = Http().bindAndHandleAsync(handle, hostname, port, settings = settings)
      val b1 = Await.result(binding, 3.seconds.dilated)

      def runRequest(uri: Uri): Unit =
        Http().outgoingConnection(hostname, port)
          .runWith(Source.single(HttpRequest(uri = uri)), Sink.head)

      runRequest("/slow")

      // wait until first request was received (but not yet answered)
      val slowTime = Await.result(receivedSlow.future, 2.second.dilated)

      // should be blocked by the slow connection still being open
      runRequest("/fast")

      val fastTime = Await.result(receivedFast.future, 2.second.dilated)
      val diff = fastTime - slowTime
      diff should be > 1000000000L // the diff must be at least the time to complete the first request and to close the first connection

      Await.result(b1.unbind(), 1.second.dilated)
    }

    "Remote-Address header" should {
      def handler(req: HttpRequest): HttpResponse = {
        val entity = req.header[headers.`Remote-Address`].flatMap(_.address.toIP).flatMap(_.port).toString
        HttpResponse(entity = entity)
      }

      "be added when using bind API" in new RemoteAddressTestScenario {
        def createBinding(): Future[ServerBinding] =
          Http().bind(hostname, port, settings = settings)
            .map(_.flow.join(Flow[HttpRequest].map(handler)).run())
            .to(Sink.ignore)
            .run()
      }

      "be added when using bindAndHandle API" in new RemoteAddressTestScenario {
        def createBinding(): Future[ServerBinding] =
          Http().bindAndHandle(Flow[HttpRequest].map(handler), hostname, port, settings = settings)
      }

      "be added when using bindAndHandleSync API" in new RemoteAddressTestScenario {
        def createBinding(): Future[ServerBinding] =
          Http().bindAndHandleSync(handler, hostname, port, settings = settings)
      }

      abstract class RemoteAddressTestScenario {
        val (hostname, port) = SocketUtil.temporaryServerHostnameAndPort()

        val settings = ServerSettings(system).withRemoteAddressHeader(true)
        def createBinding(): Future[ServerBinding]

        val binding = createBinding()
        val b1 = Await.result(binding, 3.seconds.dilated)

        val (conn, response) =
          Source.single(HttpRequest(uri = "/abc"))
            .viaMat(Http().outgoingConnection(hostname, port))(Keep.right)
            .toMat(Sink.head)(Keep.both)
            .run()

        val r = Await.result(response, 1.second.dilated)
        val c = Await.result(conn, 1.second.dilated)
        Await.result(b1.unbind(), 1.second.dilated)

        toStrict(r.entity).data.utf8String shouldBe s"Some(${c.localAddress.getPort})"
      }
    }

    "timeouts" should {
      def bindServer(hostname: String, port: Int, serverIdleTimeout: FiniteDuration): (Promise[Long], ServerBinding) = {
        val s = ServerSettings(system)
        val settings = s.withTimeouts(s.timeouts.withIdleTimeout(serverIdleTimeout))

        val receivedRequest = Promise[Long]()

        def handle(req: HttpRequest): Future[HttpResponse] = {
          receivedRequest.complete(Success(System.nanoTime()))
          Promise().future // never complete the request with a response; we're waiting for the timeout to happen, nothing else
        }

        val binding = Http().bindAndHandleAsync(handle, hostname, port, settings = settings)
        val b1 = Await.result(binding, 3.seconds.dilated)
        (receivedRequest, b1)
      }

      "support server timeouts" should {
        "close connection with idle client after idleTimeout" in {
          val serverIdleTimeout = 300.millis
          val (hostname, port) = SocketUtil.temporaryServerHostnameAndPort()
          val (receivedRequest: Promise[Long], b1: ServerBinding) = bindServer(hostname, port, serverIdleTimeout)

          try {
            def runIdleRequest(uri: Uri): Future[HttpResponse] = {
              val itNeverEnds = Chunked.fromData(ContentTypes.`text/plain(UTF-8)`, Source.maybe[ByteString])
              Http().outgoingConnection(hostname, port)
                .runWith(Source.single(HttpRequest(PUT, uri, entity = itNeverEnds)), Sink.head)
                ._2
            }

            val clientsResponseFuture = runIdleRequest("/")

            // await for the server to get the request
            val serverReceivedRequestAtNanos = Await.result(receivedRequest.future, 2.seconds.dilated)

            // waiting for the timeout to happen on the client
            intercept[StreamTcpException] {
              Await.result(clientsResponseFuture, 2.second.dilated)
            }

            (System.nanoTime() - serverReceivedRequestAtNanos).millis should be >= serverIdleTimeout
          } finally Await.result(b1.unbind(), 1.second.dilated)
        }
      }

      "support client timeouts" should {
        "close connection with idle server after idleTimeout (using connection level client API)" in {
          val serverIdleTimeout = 10.seconds.dilated

          val clientIdleTimeout = 345.millis.dilated
          val clientSettings = ClientConnectionSettings(system).withIdleTimeout(clientIdleTimeout)

          val (receivedRequest: Promise[Long], binding: ServerBinding) = bindServer("localhost", port = 0, serverIdleTimeout)

          try {
            def runRequest(uri: Uri): Future[HttpResponse] = {
              val itNeverSends = Chunked.fromData(ContentTypes.`text/plain(UTF-8)`, Source.maybe[ByteString])
              Http().outgoingConnection(binding.localAddress.getHostName, binding.localAddress.getPort, settings = clientSettings)
                .runWith(Source.single(HttpRequest(POST, uri, entity = itNeverSends)), Sink.head)
                ._2
            }

            val clientSentRequestAtNanos = System.nanoTime()
            val clientsResponseFuture = runRequest("/")

            // await for the server to get the request
            val serverReceivedRequestAtNanos = Await.result(receivedRequest.future, 2.seconds.dilated)

            // waiting for the timeout to happen on the client
            intercept[TimeoutException] {
              Await.result(clientsResponseFuture, 2.second.dilated)
            }
            val clientSawTimeoutAtNanos = System.nanoTime()
            (clientSawTimeoutAtNanos - clientSentRequestAtNanos).nanos should be >= clientIdleTimeout
            (clientSawTimeoutAtNanos - serverReceivedRequestAtNanos).nanos should be < serverIdleTimeout
          } finally Await.result(binding.unbind(), 1.second.dilated)
        }

        "close connection with idle server after idleTimeout (using pool level client API)" in {
          val serverTimeout = 10.seconds.dilated

          val cs = ConnectionPoolSettings(system)
          val clientTimeout = 345.millis.dilated
          val clientPoolSettings = cs.withIdleTimeout(clientTimeout)

          val (hostname, port) = SocketUtil.temporaryServerHostnameAndPort()
          val (receivedRequest: Promise[Long], b1: ServerBinding) = bindServer(hostname, port, serverTimeout)

          try {
            val pool = Http().cachedHostConnectionPool[Int](hostname, port, clientPoolSettings)

            def runRequest(uri: Uri): Future[(Try[HttpResponse], Int)] = {
              val itNeverSends = Chunked.fromData(ContentTypes.`text/plain(UTF-8)`, Source.maybe[ByteString])
              Source.single(HttpRequest(POST, uri, entity = itNeverSends) → 1)
                .via(pool)
                .runWith(Sink.head)
            }

            val clientsResponseFuture = runRequest("/")

            // await for the server to get the request
            val serverReceivedRequestAtNanos = Await.result(receivedRequest.future, 2.seconds.dilated)

            // waiting for the timeout to happen on the client
            intercept[TimeoutException] {
              Await.result(clientsResponseFuture, 2.second.dilated)
            }
            val actualTimeout = System.nanoTime() - serverReceivedRequestAtNanos
            actualTimeout.nanos should be >= clientTimeout
            actualTimeout.nanos should be < serverTimeout
          } finally Await.result(b1.unbind(), 1.second.dilated)
        }

        "close connection with idle server after idleTimeout (using request level client API)" in {
          val serverTimeout = 10.seconds.dilated

          val cs = ConnectionPoolSettings(system)
          val clientTimeout = 345.millis.dilated
          val clientPoolSettings = cs.withIdleTimeout(clientTimeout)

          val (hostname, port) = SocketUtil.temporaryServerHostnameAndPort()
          val (receivedRequest: Promise[Long], b1: ServerBinding) = bindServer(hostname, port, serverTimeout)

          try {
            def runRequest(uri: Uri): Future[HttpResponse] = {
              val itNeverSends = Chunked.fromData(ContentTypes.`text/plain(UTF-8)`, Source.maybe[ByteString])
              Http().singleRequest(HttpRequest(POST, uri, entity = itNeverSends), settings = clientPoolSettings)
            }

            val clientsResponseFuture = runRequest(s"http://$hostname:$port/")

            // await for the server to get the request
            val serverReceivedRequestAtNanos = Await.result(receivedRequest.future, 2.seconds.dilated)

            // waiting for the timeout to happen on the client
            intercept[TimeoutException] {
              Await.result(clientsResponseFuture, 3.second.dilated)
            }
            val actualTimeout = System.nanoTime() - serverReceivedRequestAtNanos
            actualTimeout.nanos should be >= clientTimeout
            actualTimeout.nanos should be < serverTimeout
          } finally Await.result(b1.unbind(), 1.second.dilated)
        }
      }
    }

    "log materialization errors in `bindAndHandle`" which {
      "are triggered in `mapMaterialized`" in Utils.assertAllStagesStopped {
        // FIXME racy feature, needs https://github.com/akka/akka/issues/17849 to be fixed
        pending
        val (hostname, port) = SocketUtil.temporaryServerHostnameAndPort()
        val flow = Flow[HttpRequest].map(_ ⇒ HttpResponse()).mapMaterializedValue(_ ⇒ sys.error("BOOM"))
        val binding = Http(system2).bindAndHandle(flow, hostname, port)(materializer2)
        val b1 = Await.result(binding, 1.seconds.dilated)

        EventFilter[RuntimeException](message = "BOOM", occurrences = 1).intercept {
          val (_, responseFuture) =
            Http(system2).outgoingConnection(hostname, port).runWith(Source.single(HttpRequest()), Sink.head)(materializer2)
          try Await.result(responseFuture, 5.seconds.dilated).status should ===(StatusCodes.InternalServerError)
          catch {
            case _: StreamTcpException ⇒
            // Also fine, depends on the race between abort and 500, caused by materialization panic which
            // tries to tear down everything, but the order is nondeterministic
          }
        }(system2)
        Await.result(b1.unbind(), 1.second.dilated)
      }(materializer2)

      "stop stages on failure" in Utils.assertAllStagesStopped {
        val (hostname, port) = SocketUtil.temporaryServerHostnameAndPort()
        val stageCounter = new AtomicLong(0)
        val cancelCounter = new AtomicLong(0)
        val stage: GraphStage[FlowShape[HttpRequest, HttpResponse]] = new GraphStage[FlowShape[HttpRequest, HttpResponse]] {
          val in = Inlet[HttpRequest]("request.in")
          val out = Outlet[HttpResponse]("response.out")

          override def shape: FlowShape[HttpRequest, HttpResponse] = FlowShape.of(in, out)

          override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) with InHandler with OutHandler {
            override def preStart(): Unit = stageCounter.incrementAndGet()
            override def postStop(): Unit = stageCounter.decrementAndGet()
            override def onPush(): Unit = push(out, HttpResponse(entity = stageCounter.get().toString))
            override def onPull(): Unit = pull(in)
            override def onDownstreamFinish(): Unit = cancelCounter.incrementAndGet()

            setHandlers(in, out, this)
          }
        }

        def performFaultyRequest() = {
          val socket = new Socket(hostname, port)
          val os = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream, "UTF8"))

          os.write("YOLO")
          os.close()

          socket.close()
        }

        def performValidRequest() = Http().outgoingConnection(hostname, port).runWith(Source.single(HttpRequest()), Sink.ignore)

        def assertCounters(stage: Int, cancel: Int) = eventually(timeout(1.second.dilated)) {
          stageCounter.get shouldEqual stage
          cancelCounter.get shouldEqual cancel
        }

        val bind = Await.result(Http().bindAndHandle(Flow.fromGraph(stage), hostname, port)(materializer2), 1.seconds.dilated)

        performValidRequest()
        assertCounters(0, 1)

        performFaultyRequest()
        assertCounters(0, 2)

        performValidRequest()
        assertCounters(0, 3)

        Await.result(bind.unbind(), 1.second.dilated)
      }(materializer2)
    }

    "properly complete a simple request/response cycle" in Utils.assertAllStagesStopped {
      new TestSetup {
        val (clientOut, clientIn) = openNewClientConnection()
        val (serverIn, serverOut) = acceptConnection()

        val clientOutSub = clientOut.expectSubscription()
        clientOutSub.expectRequest()
        clientOutSub.sendNext(HttpRequest(uri = "/abc"))

        val serverInSub = serverIn.expectSubscription()
        serverInSub.request(1)
        serverIn.expectNext().uri shouldEqual Uri(s"http://$hostname:$port/abc")

        val serverOutSub = serverOut.expectSubscription()
        serverOutSub.expectRequest()
        serverOutSub.sendNext(HttpResponse(entity = "yeah"))

        val clientInSub = clientIn.expectSubscription()
        clientInSub.request(1)
        val response = clientIn.expectNext()
        toStrict(response.entity) shouldEqual HttpEntity("yeah")

        clientOutSub.sendComplete()
        serverIn.expectComplete()
        serverOutSub.expectCancellation()
        clientIn.expectComplete()

        binding.foreach(_.unbind())
      }
    }

    "properly complete a chunked request/response cycle" in Utils.assertAllStagesStopped {
      new TestSetup {
        val (clientOut, clientIn) = openNewClientConnection()
        val (serverIn, serverOut) = acceptConnection()

        val chunks = List(Chunk("abc"), Chunk("defg"), Chunk("hijkl"), LastChunk)
        val chunkedContentType: ContentType = MediaTypes.`application/base64` withCharset HttpCharsets.`UTF-8`
        val chunkedEntity = HttpEntity.Chunked(chunkedContentType, Source(chunks))

        val clientOutSub = clientOut.expectSubscription()
        clientOutSub.sendNext(HttpRequest(POST, "/chunked", List(Accept(MediaRanges.`*/*`)), chunkedEntity))

        val serverInSub = serverIn.expectSubscription()
        serverInSub.request(1)
        private val HttpRequest(POST, uri, List(Accept(Seq(MediaRanges.`*/*`)), Host(_, _), `User-Agent`(_)),
          Chunked(`chunkedContentType`, chunkStream), HttpProtocols.`HTTP/1.1`) = serverIn.expectNext() mapHeaders (_.filterNot(_.is("timeout-access")))
        uri shouldEqual Uri(s"http://$hostname:$port/chunked")
        Await.result(chunkStream.limit(5).runWith(Sink.seq), 1000.millis.dilated) shouldEqual chunks

        val serverOutSub = serverOut.expectSubscription()
        serverOutSub.expectRequest()
        serverOutSub.sendNext(HttpResponse(206, List(Age(42)), chunkedEntity))

        val clientInSub = clientIn.expectSubscription()
        clientInSub.request(1)
        val HttpResponse(StatusCodes.PartialContent, List(Age(42), Server(_), Date(_)),
          Chunked(`chunkedContentType`, chunkStream2), HttpProtocols.`HTTP/1.1`) = clientIn.expectNext()
        Await.result(chunkStream2.limit(1000).runWith(Sink.seq), 1000.millis.dilated) shouldEqual chunks

        clientOutSub.sendComplete()
        serverInSub.request(1)
        serverIn.expectComplete()
        serverOutSub.expectCancellation()
        clientInSub.request(1)
        clientIn.expectComplete()

        connSourceSub.cancel()
      }
    }
    "complete a request/response when request has `Connection: close` set" in Utils.assertAllStagesStopped {
      // In akka/akka#19542 / akka/akka-http#459 it was observed that when an akka-http closes the connection after
      // a request, the TCP connection is sometimes aborted. Aborting means that `socket.close` is called with SO_LINGER = 0
      // which removes the socket immediately from the OS network stack. This might happen with or without having sent
      // a FIN frame first and with or without actively sending a RST frame. However, if the client has not received all data
      // yet when the next ACK arrives at the server it will respond with a RST package. This will lead to a
      // broken connection and a "Connection reset by peer" error on the client.
      //
      // The original cause for connection abortion was a race between connection completion and cancellation reaching
      // each side of the Tcp connection stream.
      //
      // This reproducer tries to increase chances that bytes are still in flight when the connection is closed to trigger
      // the error more reliably.

      // The original reproducer suggested decreasing the MTU for the loopback device. We emulate a low
      // MTU by setting super small network buffers. This means more TCP round-trips between server and client
      // increasing the chances that the problem occurs.
      val serverToClientNetworkBufferSize = 1000
      val responseSize = 200000

      val (hostname, port) = SocketUtil.temporaryServerHostnameAndPort()
      def request(i: Int) = HttpRequest(uri = s"http://$hostname:$port/$i", headers = headers.Connection("close") :: Nil)
      def response(req: HttpRequest) = HttpResponse(entity = HttpEntity.Strict(ContentTypes.`text/plain(UTF-8)`, ByteString(req.uri.path.toString.takeRight(1) * responseSize)))

      // settings adapting network buffer sizes
      val serverSettings = ServerSettings(system).withSocketOptions(SO.SendBufferSize(serverToClientNetworkBufferSize) :: Nil)
      val clientSettings = ConnectionPoolSettings(system).withConnectionSettings(ClientConnectionSettings(system).withSocketOptions(SO.ReceiveBufferSize(serverToClientNetworkBufferSize) :: Nil))

      val server = Http().bindAndHandleSync(response, hostname, port, settings = serverSettings)
      def runOnce(i: Int) =
        Http().singleRequest(request(i), settings = clientSettings).futureValue
          .entity.dataBytes.runFold(ByteString.empty) { (prev, cur) ⇒
            val res = prev ++ cur
            system.log.debug(s"Received ${res.size} of [${res.take(1).utf8String}]")
            res
          }.futureValue
          .size shouldBe responseSize

      try {
        (1 to 10).foreach(runOnce)
      } finally server.foreach(_.unbind())
    }

    "complete a request/response over https when request has `Connection: close` set" in Utils.assertAllStagesStopped {
      // akka/akka-http#1219
      val serverToClientNetworkBufferSize = 1000
      val (hostname, port) = SocketUtil.temporaryServerHostnameAndPort()
      val request = HttpRequest(uri = s"https://$hostname:$port", headers = headers.Connection("close") :: Nil)

      // settings adapting network buffer sizes
      val serverSettings = ServerSettings(system).withSocketOptions(SO.SendBufferSize(serverToClientNetworkBufferSize) :: Nil)
      val clientSettings = ConnectionPoolSettings(system).withConnectionSettings(ClientConnectionSettings(system).withSocketOptions(SO.ReceiveBufferSize(serverToClientNetworkBufferSize) :: Nil))

      val serverConnectionContext = ExampleHttpContexts.exampleServerContext
      // Disable hostname verification as ExampleHttpContexts.exampleClientContext sets hostname as akka.example.org
      val sslConfigSettings = SSLConfigSettings().withLoose(SSLLooseConfig().withDisableHostnameVerification(true))
      val sslConfig = AkkaSSLConfig().withSettings(sslConfigSettings)
      val clientConnectionContext = ConnectionContext.https(ExampleHttpContexts.exampleClientContext.sslContext, Some(sslConfig))

      val entity = Array.fill[Char](999999)('0').mkString + "x"
      val routes: Flow[HttpRequest, HttpResponse, Any] = Flow[HttpRequest].map { _ ⇒ HttpResponse(entity = entity) }
      val serverBinding =
        Http()
          .bindAndHandle(routes, hostname, port, connectionContext = serverConnectionContext, settings = serverSettings)
          .futureValue

      Http()
        .singleRequest(request, connectionContext = clientConnectionContext, settings = clientSettings)
        .futureValue
        .entity.dataBytes.runFold(ByteString.empty)(_ ++ _).futureValue.utf8String shouldEqual entity

      serverBinding.unbind()
    }

    "complete a request/response over https when server closes connection without close_notify" in Utils.assertAllStagesStopped {
      val source = TestPublisher.probe[ByteString]()

      def handler(req: HttpRequest): HttpResponse =
        HttpResponse(entity = HttpEntity.CloseDelimited(ContentTypes.`application/octet-stream`, Source.fromPublisher(source)))

      val serverSideTls = Http().sslTlsStage(ExampleHttpContexts.exampleServerContext, akka.stream.Server)
      val clientSideTls = Http().sslTlsStage(ExampleHttpContexts.exampleClientContext, akka.stream.Client, Some("akka.example.org" → 8080))

      val server: Flow[ByteString, ByteString, Any] =
        Http().serverLayer()
          .atop(serverSideTls)
          .reversed
          .join(Flow[HttpRequest].map(handler))

      val client =
        Http().clientLayer(Host("akka.example.org", 8080))
          .atop(clientSideTls)

      val killSwitch = KillSwitches.shared("kill-transport")

      val pipe: Flow[HttpRequest, HttpResponse, Any] =
        client
          .atop(BidiFlow.fromFlows(Flow[ByteString], killSwitch.flow[ByteString])) // kill switch will kill server -> client connection without close_notify
          .join(server)

      val response =
        Source.single(HttpRequest())
          .via(pipe)
          .runWith(Sink.head)
          .awaitResult(10.seconds)

      val sinkProbe = ByteStringSinkProbe()
      response.entity.dataBytes.runWith(sinkProbe.sink)

      source.sendNext(ByteString("abcdef"))
      sinkProbe.expectUtf8EncodedString("abcdef")

      source.sendNext(ByteString("ghij"))
      sinkProbe.expectUtf8EncodedString("ghij")

      killSwitch.shutdown() // simulate FIN in server -> client direction
      // akka-http is currently lenient wrt TLS truncation which is *not* reported to the user
      // FIXME: if https://github.com/akka/akka-http/issues/235 is ever fixed, expect an error here
      sinkProbe.expectComplete()
    }

    "properly complete a simple request/response cycle when `modeled-header-parsing = off`" in Utils.assertAllStagesStopped {
      new TestSetup {
        override def configOverrides = "akka.http.parsing.modeled-header-parsing = off"

        val (clientOut, clientIn) = openNewClientConnection()
        val (serverIn, serverOut) = acceptConnection()

        val clientOutSub = clientOut.expectSubscription()
        clientOutSub.expectRequest()
        clientOutSub.sendNext(HttpRequest(uri = "/abc"))

        val serverInSub = serverIn.expectSubscription()
        serverInSub.request(1)
        serverIn.expectNext().uri shouldEqual Uri(s"http://$hostname:$port/abc")

        val serverOutSub = serverOut.expectSubscription()
        serverOutSub.expectRequest()
        serverOutSub.sendNext(HttpResponse(entity = "yeah"))

        val clientInSub = clientIn.expectSubscription()
        clientInSub.request(1)
        val response = clientIn.expectNext()
        toStrict(response.entity) shouldEqual HttpEntity("yeah")

        clientOutSub.sendComplete()
        serverIn.expectComplete()
        serverOutSub.expectCancellation()
        clientIn.expectComplete()

        binding.foreach(_.unbind())
      }
    }

    "be able to deal with eager closing of the request stream on the client side" in Utils.assertAllStagesStopped {
      new TestSetup {
        val (clientOut, clientIn) = openNewClientConnection()
        val (serverIn, serverOut) = acceptConnection()

        val clientOutSub = clientOut.expectSubscription()
        clientOutSub.sendNext(HttpRequest(uri = "/abc"))
        clientOutSub.sendComplete()
        // complete early

        val serverInSub = serverIn.expectSubscription()
        serverInSub.request(1)
        serverIn.expectNext().uri shouldEqual Uri(s"http://$hostname:$port/abc")

        val serverOutSub = serverOut.expectSubscription()
        serverOutSub.expectRequest()
        serverOutSub.sendNext(HttpResponse(entity = "yeah"))

        val clientInSub = clientIn.expectSubscription()
        clientInSub.request(1)
        val response = clientIn.expectNext()
        toStrict(response.entity) shouldEqual HttpEntity("yeah")

        serverIn.expectComplete()
        serverOutSub.expectCancellation()
        clientIn.expectComplete()

        connSourceSub.cancel()
      }
    }
  }

  override def afterAll() = {
    TestKit.shutdownActorSystem(system)
    TestKit.shutdownActorSystem(system2)
  }

  class TestSetup {
    val (hostname, port) = SocketUtil.temporaryServerHostnameAndPort()
    def configOverrides = ""

    // automatically bind a server
    val (connSource, binding: Future[ServerBinding]) = {
      val settings = configOverrides.toOption.fold(ServerSettings(system))(ServerSettings(_))
      val connections = Http().bind(hostname, port, settings = settings)
      val probe = TestSubscriber.manualProbe[Http.IncomingConnection]
      val binding = connections.toMat(Sink.fromSubscriber(probe))(Keep.left).run()
      (probe, binding)
    }
    val connSourceSub = connSource.expectSubscription()

    def openNewClientConnection(settings: ClientConnectionSettings = ClientConnectionSettings(system)) = {
      val requestPublisherProbe = TestPublisher.manualProbe[HttpRequest]()
      val responseSubscriberProbe = TestSubscriber.manualProbe[HttpResponse]()

      val connectionFuture = Source.fromPublisher(requestPublisherProbe)
        .viaMat(Http().outgoingConnection(hostname, port, settings = settings))(Keep.right)
        .to(Sink.fromSubscriber(responseSubscriberProbe)).run()

      val connection = Await.result(connectionFuture, 3.seconds.dilated)

      connection.remoteAddress.getHostName shouldEqual hostname
      connection.remoteAddress.getPort shouldEqual port
      requestPublisherProbe → responseSubscriberProbe
    }

    def acceptConnection(): (TestSubscriber.ManualProbe[HttpRequest], TestPublisher.ManualProbe[HttpResponse]) = {
      connSourceSub.request(1)
      val incomingConnection = connSource.expectNext()
      val sink = Sink.asPublisher[HttpRequest](false)
      val source = Source.asSubscriber[HttpResponse]

      val handler = Flow.fromSinkAndSourceMat(sink, source)(Keep.both)

      val (pub, sub) = incomingConnection.handleWith(handler)
      val requestSubscriberProbe = TestSubscriber.manualProbe[HttpRequest]()
      val responsePublisherProbe = TestPublisher.manualProbe[HttpResponse]()

      pub.subscribe(requestSubscriberProbe)
      responsePublisherProbe.subscribe(sub)
      requestSubscriberProbe → responsePublisherProbe
    }

    def openClientSocket() = new Socket(hostname, port)

    def write(socket: Socket, data: String) = {
      val writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream))
      writer.write(data)
      writer.flush()
      writer
    }

    def readAll(socket: Socket)(reader: BufferedReader = new BufferedReader(new InputStreamReader(socket.getInputStream))): (String, BufferedReader) = {
      val sb = new java.lang.StringBuilder
      val cbuf = new Array[Char](256)
      @tailrec def drain(): (String, BufferedReader) = reader.read(cbuf) match {
        case -1 ⇒ sb.toString → reader
        case n  ⇒ sb.append(cbuf, 0, n); drain()
      }
      drain()
    }
  }

  def toStrict(entity: HttpEntity): HttpEntity.Strict = Await.result(entity.toStrict(500.millis.dilated), 1.second.dilated)
}
