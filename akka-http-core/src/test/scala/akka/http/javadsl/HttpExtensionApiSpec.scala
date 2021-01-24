/*
 * Copyright (C) 2017-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl

import java.net.InetSocketAddress
import java.util.Optional
import java.util.concurrent.{ CompletableFuture, CompletionStage, TimeUnit }

import akka.NotUsed
import akka.http.javadsl.ConnectHttp._
import akka.http.javadsl.model.ws._
import akka.http.javadsl.settings.{ ClientConnectionSettings, ConnectionPoolSettings, ServerSettings }
import akka.japi.Pair
import akka.event.NoLogging
import akka.http.impl.util.AkkaSpecWithMaterializer
import akka.http.javadsl.model._
import akka.japi.function.Function
import akka.stream.javadsl.{ Flow, Keep, Sink, Source }
import akka.stream.testkit.TestSubscriber

import scala.util.Try

class HttpExtensionApiSpec extends AkkaSpecWithMaterializer(
  """
    akka.http.server.log-unencrypted-network-bytes = 100
    akka.http.client.log-unencrypted-network-bytes = 100
    akka.http.server.request-timeout = infinite
    akka.io.tcp.trace-logging = true
  """
) {

  // tries to cover all surface area of javadsl.Http

  type Host = String
  type Port = Int

  val http = Http.get(system)
  val connectionContext = ConnectionContext.noEncryption()
  val serverSettings = ServerSettings.create(system)
  val poolSettings = ConnectionPoolSettings.create(system)
  val loggingAdapter = NoLogging

  val successResponse = HttpResponse.create().withStatus(200)

  val httpSuccessFunction = new Function[HttpRequest, HttpResponse] {
    @throws(classOf[Exception])
    override def apply(param: HttpRequest): HttpResponse = successResponse
  }

  val asyncHttpSuccessFunction = new Function[HttpRequest, CompletionStage[HttpResponse]] {
    @throws(classOf[Exception])
    override def apply(param: HttpRequest): CompletionStage[HttpResponse] =
      CompletableFuture.completedFuture(successResponse)
  }

  "The Java HTTP extension" should {

    // all four bind method overloads
    "properly bind a server (with three parameters)" in {
      val probe = TestSubscriber.manualProbe[IncomingConnection]()
      val binding = http.newServerAt("127.0.0.1", 0).connectionSource()
        .toMat(Sink.fromSubscriber(probe), Keep.left)
        .run(materializer)
      val sub = probe.expectSubscription()
      waitFor(binding).unbind()
      sub.cancel()
    }

    "properly bind a server (with four parameters)" in {
      val probe = TestSubscriber.manualProbe[IncomingConnection]()
      val binding = http.newServerAt("127.0.0.1", 0).connectionSource()
        .toMat(Sink.fromSubscriber(probe), Keep.left)
        .run(materializer)
      val sub = probe.expectSubscription()
      waitFor(binding).unbind()
      sub.cancel()
    }

    "properly bind a server (with five parameters)" in {
      val probe = TestSubscriber.manualProbe[IncomingConnection]()
      val binding = http.newServerAt("127.0.0.1", 0)
        .withSettings(serverSettings)
        .connectionSource()
        .toMat(Sink.fromSubscriber(probe), Keep.left)
        .run(materializer)
      val sub = probe.expectSubscription()
      waitFor(binding).unbind()
      sub.cancel()
    }

    "properly bind a server (with six parameters)" in {
      val probe = TestSubscriber.manualProbe[IncomingConnection]()
      val binding = http.newServerAt("127.0.0.1", 0)
        .withSettings(serverSettings)
        .logTo(loggingAdapter)
        .connectionSource()
        .toMat(Sink.fromSubscriber(probe), Keep.left)
        .run(materializer)
      val sub = probe.expectSubscription()
      waitFor(binding).unbind()
      sub.cancel()
    }

    abstract class TestSetup {
      def bind(): CompletionStage[ServerBinding]

      val flow: Flow[HttpRequest, HttpResponse, NotUsed] = akka.stream.scaladsl.Flow[HttpRequest]
        .map(req => HttpResponse.create())
        .asJava
      val binding = waitFor(bind())
      val (host, port) = (binding.localAddress.getHostString, binding.localAddress.getPort)

      val completion =
        Source.single(HttpRequest.create("/abc"))
          .via(http.outgoingConnection(toHost(host, port)))
          .runWith(Sink.head[HttpResponse](), materializer)

      waitFor(completion)
      waitFor(binding.unbind())
    }

    // this cover both bind and single request
    "properly bind and handle a server with a flow (simple)" in new TestSetup {
      def bind() = http.newServerAt("127.0.0.1", 0).bindFlow(flow)
    }

    "properly bind and handle a server with a flow (with settings and log)" in new TestSetup {
      def bind() = http.newServerAt("127.0.0.1", 0).withSettings(serverSettings).logTo(loggingAdapter).bindFlow(flow)
    }

    "properly bind and handle a server with a synchronous function (simple)" in new TestSetup {
      def bind() = http.newServerAt("127.0.0.1", 0).bindSync(httpSuccessFunction)
    }

    "properly bind and handle a server with a synchronous (with settings and log)" in new TestSetup {
      def bind() = http.newServerAt("127.0.0.1", 0).withSettings(serverSettings).logTo(loggingAdapter).bindSync(httpSuccessFunction)
    }

    "properly bind and handle a server with an asynchronous function (simple)" in new TestSetup {
      def bind() = http.newServerAt("127.0.0.1", 0).bind(asyncHttpSuccessFunction)
    }

    "properly bind and handle a server with an asynchronous function (with settings and log)" in new TestSetup {
      def bind() = http.newServerAt("127.0.0.1", 0).withSettings(serverSettings).logTo(loggingAdapter).bind(asyncHttpSuccessFunction)
    }

    "have serverLayer methods" in {
      // TODO actually cover these with runtime tests, compile only for now
      pending

      http.serverLayer()

      val serverSettings = ServerSettings.create(system)
      http.serverLayer(serverSettings)

      val remoteAddress = Optional.empty[InetSocketAddress]()
      http.serverLayer(serverSettings, remoteAddress)

      val loggingAdapter = NoLogging
      http.serverLayer(serverSettings, remoteAddress, loggingAdapter)
    }

    "create a cached connection pool (with a ConnectToHttp and a materializer)" in {
      val (host, port, binding) = runServer()

      val poolFlow: Flow[Pair[HttpRequest, NotUsed], Pair[Try[HttpResponse], NotUsed], HostConnectionPool] =
        http.cachedHostConnectionPool[NotUsed](toHost(host, port))

      val pair: Pair[HostConnectionPool, CompletionStage[Pair[Try[HttpResponse], NotUsed]]] =
        Source.single(new Pair(HttpRequest.GET(s"http://$host:$port/"), NotUsed.getInstance()))
          .viaMat(poolFlow, Keep.right[NotUsed, HostConnectionPool])
          .toMat(
            Sink.head(),
            Keep.both[HostConnectionPool, CompletionStage[Pair[Try[HttpResponse], NotUsed]]])
          .run(materializer)

      waitFor(pair.second).first.isSuccess should be(true)
      waitFor(binding.unbind())
    }

    "create a cached connection pool to a https server (with four parameters)" in {
      // requires https
      pending
      val (host, port, binding) = runServer()

      val poolFlow: Flow[Pair[HttpRequest, NotUsed], Pair[Try[HttpResponse], NotUsed], HostConnectionPool] =
        http.cachedHostConnectionPool[NotUsed](toHost(host, port), poolSettings, loggingAdapter)

      val pair: Pair[HostConnectionPool, CompletionStage[Pair[Try[HttpResponse], NotUsed]]] =
        Source.single(new Pair(HttpRequest.GET(s"http://$host:$port/"), NotUsed.getInstance()))
          .viaMat(poolFlow, Keep.right[NotUsed, HostConnectionPool])
          .toMat(
            Sink.head(),
            Keep.both[HostConnectionPool, CompletionStage[Pair[Try[HttpResponse], NotUsed]]])
          .run(materializer)

      waitFor(pair.second).first.get.status() should be(StatusCodes.OK)
      waitFor(binding.unbind())
    }

    "create a cached connection pool (with a String and a Materializer)" in {
      val (host, port, binding) = runServer()

      val poolFlow: Flow[Pair[HttpRequest, NotUsed], Pair[Try[HttpResponse], NotUsed], HostConnectionPool] =
        http.cachedHostConnectionPool[NotUsed](s"http://$host:$port")

      val pair: Pair[HostConnectionPool, CompletionStage[Pair[Try[HttpResponse], NotUsed]]] =
        Source.single(new Pair(HttpRequest.GET(s"http://$host:$port/"), NotUsed.getInstance()))
          .viaMat(poolFlow, Keep.right[NotUsed, HostConnectionPool])
          .toMat(
            Sink.head(),
            Keep.both[HostConnectionPool, CompletionStage[Pair[Try[HttpResponse], NotUsed]]])
          .run(materializer)

      waitFor(pair.second).first.get.status() should be(StatusCodes.OK)
      waitFor(binding.unbind())
    }

    "create a host connection pool (with a ConnectHttp and a Materializer)" in {
      val (host, port, binding) = runServer()

      val poolFlow = http.newHostConnectionPool[NotUsed](toHost(host, port), materializer)

      val pair: Pair[HostConnectionPool, CompletionStage[Pair[Try[HttpResponse], NotUsed]]] =
        Source.single(new Pair(get(host, port), NotUsed.getInstance()))
          .viaMat(poolFlow, Keep.right[NotUsed, HostConnectionPool])
          .toMat(
            Sink.head(),
            Keep.both[HostConnectionPool, CompletionStage[Pair[Try[HttpResponse], NotUsed]]])
          .run(materializer)

      waitFor(pair.second).first.get.status() should be(StatusCodes.OK)
      pair.first.shutdown()
      waitFor(binding.unbind())
    }

    "create a host connection pool to a https server (with four parameters)" in {
      // requires https
      pending
      val (host, port, binding) = runServer()

      val poolFlow = http.newHostConnectionPool[NotUsed](toHost(host, port), poolSettings, loggingAdapter, materializer)

      val pair: Pair[HostConnectionPool, CompletionStage[Pair[Try[HttpResponse], NotUsed]]] =
        Source.single(new Pair(get(host, port), NotUsed.getInstance()))
          .viaMat(poolFlow, Keep.right[NotUsed, HostConnectionPool])
          .toMat(
            Sink.head(),
            Keep.both[HostConnectionPool, CompletionStage[Pair[Try[HttpResponse], NotUsed]]])
          .run(materializer)

      waitFor(pair.second).first.get.status() should be(StatusCodes.OK)
      pair.first.shutdown()
      waitFor(binding.unbind())
    }

    "create a host connection pool (with a String and a Materializer)" in {
      val (host, port, binding) = runServer()

      val poolFlow = http.newHostConnectionPool[NotUsed](s"http://$host:$port", materializer)

      val pair: Pair[HostConnectionPool, CompletionStage[Pair[Try[HttpResponse], NotUsed]]] =
        Source.single(new Pair(get(host, port), NotUsed.getInstance()))
          .viaMat(poolFlow, Keep.right[NotUsed, HostConnectionPool])
          .toMat(
            Sink.head(),
            Keep.both[HostConnectionPool, CompletionStage[Pair[Try[HttpResponse], NotUsed]]])
          .run(materializer)

      waitFor(pair.second).first.get.status() should be(StatusCodes.OK)
      pair.first.shutdown()
      waitFor(binding.unbind())
    }

    "allow access to the default client https context" in {
      http.defaultClientHttpsContext.isSecure should equal(true)
    }

    "have client layer methods" in {
      // TODO actually cover these with runtime tests, compile only for now
      pending
      val connectionSettings = ClientConnectionSettings.create(system)
      http.clientLayer(headers.Host.create("example.com"))
      http.clientLayer(headers.Host.create("example.com"), connectionSettings)
      http.clientLayer(headers.Host.create("example.com"), connectionSettings, loggingAdapter)
    }

    "create an outgoing connection (with a string)" in {
      // this one cannot be tested because it wants to run on port 80
      pending
      http.outgoingConnection("example.com"): Flow[HttpRequest, HttpResponse, CompletionStage[OutgoingConnection]]
    }

    "create an outgoing connection (with a ConnectHttp)" in {
      val (host, port, binding) = runServer()
      val flow = http.outgoingConnection(toHost(host, port))

      val response = Source.single(get(host, port))
        .via(flow)
        .toMat(Sink.head(), Keep.right[NotUsed, CompletionStage[HttpResponse]])
        .run(materializer)

      waitFor(response).status() should be(StatusCodes.OK)
      waitFor(binding.unbind())
    }

    "create an outgoing connection (with 6 parameters)" in {
      val (host, port, binding) = runServer()
      val flow = http.outgoingConnection(
        toHost(host, port),
        Optional.empty(),
        ClientConnectionSettings.create(system),
        NoLogging)

      val response = Source.single(get(host, port))
        .via(flow)
        .toMat(Sink.head(), Keep.right[NotUsed, CompletionStage[HttpResponse]])
        .run(materializer)

      waitFor(response).status() should be(StatusCodes.OK)
      waitFor(binding.unbind())
    }

    "allow a single request (with two parameters)" in {
      val (host, port, binding) = runServer()
      val response = http.singleRequest(HttpRequest.GET(s"http://$host:$port/"))

      waitFor(response).status() should be(StatusCodes.OK)
      waitFor(binding.unbind())
    }

    "allow a single request (with three parameters)" in {
      val (host, port, binding) = runServer()
      val response = http.singleRequest(HttpRequest.GET(s"http://$host:$port/"), http.defaultClientHttpsContext)

      waitFor(response).status() should be(StatusCodes.OK)
      waitFor(binding.unbind())
    }

    "allow a single request (with five parameters)" in {
      val (host, port, binding) = runServer()
      val response = http.singleRequest(HttpRequest.GET(s"http://$host:$port/"), http.defaultClientHttpsContext, poolSettings, loggingAdapter)

      waitFor(response).status() should be(StatusCodes.OK)
      waitFor(binding.unbind())
    }

    "interact with a websocket through a flow (with with one parameter)" in {
      val (host, port, binding) = runWebsocketServer()
      val flow = http.webSocketClientFlow(WebSocketRequest.create(s"ws://$host:$port"))
      val pair = Source.single(TextMessage.create("hello"))
        .viaMat(flow, Keep.right[NotUsed, CompletionStage[WebSocketUpgradeResponse]])
        .toMat(Sink.head[Message](), Keep.both[CompletionStage[WebSocketUpgradeResponse], CompletionStage[Message]])
        .run(materializer)

      waitFor(pair.first).response.status() should be(StatusCodes.SWITCHING_PROTOCOLS)
      waitFor(pair.second).asTextMessage.getStrictText should be("hello")
      waitFor(binding.unbind())
    }

    "interact with a websocket through a flow (with five parameters)" in {
      val (host, port, binding) = runWebsocketServer()
      val flow = http.webSocketClientFlow(WebSocketRequest.create(s"ws://$host:$port"), connectionContext, Optional.empty(), ClientConnectionSettings.create(system), loggingAdapter)
      val pair = Source.single(TextMessage.create("hello"))
        .viaMat(flow, Keep.right[NotUsed, CompletionStage[WebSocketUpgradeResponse]])
        .toMat(Sink.head[Message](), Keep.both[CompletionStage[WebSocketUpgradeResponse], CompletionStage[Message]])
        .run(materializer)

      waitFor(pair.first).response.status() should be(StatusCodes.SWITCHING_PROTOCOLS)
      waitFor(pair.second).asTextMessage.getStrictText should be("hello")
      waitFor(binding.unbind())
    }

  }

  def get(host: Host, port: Port) = HttpRequest.GET("/").addHeader(headers.Host.create(host, port))

  def runServer(): (Host, Port, ServerBinding) = {
    val server = http.newServerAt("127.0.0.1", 0).bindSync(httpSuccessFunction)

    val binding = waitFor(server)
    (binding.localAddress.getHostString, binding.localAddress.getPort, binding)
  }

  def runWebsocketServer(): (Host, Port, ServerBinding) = {
    val server = http.newServerAt("127.0.0.1", 0).bindSync(request =>
      WebSocket.handleWebSocketRequestWith(request, Flow.create[Message]())
    )

    val binding = waitFor(server)
    (binding.localAddress.getHostString, binding.localAddress.getPort, binding)
  }

  private def waitFor[T](completionStage: CompletionStage[T]): T =
    completionStage.toCompletableFuture.get(10, TimeUnit.SECONDS)
}
