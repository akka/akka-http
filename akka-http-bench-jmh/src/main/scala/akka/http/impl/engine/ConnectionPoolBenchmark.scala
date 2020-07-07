package akka.http.impl.engine

import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch

import akka.actor.ActorSystem
import akka.dispatch.ExecutionContexts
import akka.http.CommonBenchmark
import akka.http.impl.util.enhanceString_
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.settings.{ ClientConnectionSettings, ConnectionPoolSettings }
import akka.http.scaladsl.{ ClientTransport, Http }
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Flow
import akka.util.ByteString
import com.typesafe.config.ConfigFactory
import org.openjdk.jmh.annotations._

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

/**
 * A benchmark that tries to stress the pool and the client infrastructure (but nothing else)
 */
class ConnectionPoolBenchmark extends CommonBenchmark {
  import ConnectionPoolBenchmark._

  @Param(Array("1", "10", "100", "1000", "10000"))
  var maxConnections: String = _

  implicit var system: ActorSystem = _
  implicit var mat: ActorMaterializer = _
  implicit def ec: ExecutionContext = system.dispatcher

  private var poolSettings: ConnectionPoolSettings = _

  val request = HttpRequest(uri = "http://localhost:8080")

  @Benchmark
  @OperationsPerInvocation(15000)
  def singleRequest(): Unit = {
    val latch = new CountDownLatch(NumRequestsPerBatch)
    (1 to NumRequestsPerBatch).foreach { _ =>
      Http().singleRequest(request, settings = poolSettings)
        .onComplete {
          case Success(_) => latch.countDown()
          case Failure(_) => throw new IllegalStateException
        }(ExecutionContexts.parasitic)
    }

    latch.await()
  }

  @Setup
  def setup(): Unit = {
    val config =
      ConfigFactory.parseString(
        s"""
           akka.actor.default-dispatcher.fork-join-executor.parallelism-max = 1
           akka.http.host-connection-pool.max-connections = ${maxConnections}
           akka.http.host-connection-pool.max-open-requests = 16384
           akka.http.client.user-agent = akka-http-bench
        """)
        .withFallback(ConfigFactory.load())
    system = ActorSystem("AkkaHttpBenchmarkSystem", config)
    mat = ActorMaterializer()

    val responseBytes = ByteString(
      """HTTP/1.1 200 OK
        |Server: akka-http/test
        |Date: Wed, 01 Jul 2020 13:26:33 GMT
        |Content-Length: 0
        |
        |""".stripMarginWithNewline("\r\n")
    )
    val endOfRequest = ByteString("\r\n\r\n")
    // a transport that implements a complete HTTP server (yes, really, see below)
    val clientTransport =
      new ClientTransport {
        override def connectTo(host: String, port: Int, settings: ClientConnectionSettings)(implicit system: ActorSystem): Flow[ByteString, ByteString, Future[Http.OutgoingConnection]] =
          Flow[ByteString]
            // currently not needed because request will be sent in single chunk
            // .via(Framing.delimiter(ByteString("\r\n\r\n"), 1000))
            .map { req =>
              require(req.takeRight(4) == endOfRequest)
              responseBytes
            }
            .mapMaterializedValue { _ =>
              val addr = InetSocketAddress.createUnresolved(host, port)
              Future.successful(Http.OutgoingConnection(addr, addr))
            }
            // need async, otherwise server and client will run in the same GraphInterpreter and the pool
            // will only open a single connection
            .async
      }
    poolSettings =
      ConnectionPoolSettings(system).withConnectionSettings(
        ClientConnectionSettings(system).withTransport(clientTransport)
      )
  }

  @TearDown
  def tearDown(): Unit = system.terminate()
}
object ConnectionPoolBenchmark {
  val NumRequestsPerBatch = 15000
}
