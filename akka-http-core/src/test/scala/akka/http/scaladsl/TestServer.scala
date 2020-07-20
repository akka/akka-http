/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl

import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }
import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.AttributeKeys.webSocketUpgrade
import akka.http.scaladsl.model.ws._
import akka.stream._
import akka.stream.scaladsl.{ Flow, Sink, Source }
import com.typesafe.config.{ Config, ConfigFactory }
import HttpMethods._
import akka.util.{ ByteString, CompactByteString }

import scala.io.StdIn

object TestServer extends App {
  val testConf: Config = ConfigFactory.parseString("""
    akka.loglevel = info
    akka.log-dead-letters = off
    akka.stream.materializer.debug.fuzzing-mode = off
    akka.actor.serialize-creators = off
    akka.actor.serialize-messages = off
    akka.actor.default-dispatcher.throughput = 1000
    #akka.http.client.log-unencrypted-network-bytes = 100
    """)
  implicit val system = ActorSystem("ServerTest", testConf)
  import system.dispatcher

  val settings = ActorMaterializerSettings(system)
    .withFuzzing(false)
    //    .withSyncProcessingLimit(Int.MaxValue)
    .withInputBuffer(128, 128)
  implicit val fm = ActorMaterializer(settings)
  require(CompactByteString.empty != null)
  require(ByteString.empty != null)
  try {
    val binding = Http().bindAndHandleSync({
      case req @ HttpRequest(GET, Uri.Path("/"), _, _, _) =>
        req.attribute(webSocketUpgrade) match {
          case Some(upgrade) => upgrade.handleMessages(echoWebSocketService) // needed for running the autobahn test suite
          case None          => index
        }
      case HttpRequest(GET, Uri.Path("/ping"), _, _, _)  => HttpResponse(entity = "PONG!")
      case HttpRequest(GET, Uri.Path("/crash"), _, _, _) => sys.error("BOOM!")
      case req @ HttpRequest(GET, Uri.Path("/ws-greeter"), _, _, _) =>
        req.attribute(webSocketUpgrade) match {
          case Some(upgrade) => upgrade.handleMessages(greeterWebSocketService)
          case None          => HttpResponse(400, entity = "Not a valid websocket request!")
        }

      case req @ HttpRequest(GET, Uri.Path("/ws"), _, _, _) =>
        req.attribute(webSocketUpgrade) match {
          case Some(upgrade) => upgrade.handleMessages(sendDataWsService)
          case None          => HttpResponse(400, entity = "Not a valid websocket request!")
        }
      case HttpRequest(GET, Uri.Path("/test-ws"), _, _, _) =>
        @volatile var lastTime = System.nanoTime()
        val every = 10000
        Http().singleWebSocketRequest(
          WebSocketRequest("ws://localhost:9001/ws"),
          Flow[Message].mapAsync[Seq[Message]](1) {
            case BinaryMessage.Strict(data) => Future.successful(Nil)
            case BinaryMessage.Streamed(stream) =>
              //println("Got streamed")
              stream.runFold(ByteString.empty)(_ ++ _)
                .map(_ => Nil)
          }
            .fold(0) { (at, next) =>
              if (at % every == 0) {
                val lasted = System.nanoTime() - lastTime
                println(f"At $at%7d took: ${lasted}%12d ns rate: ${every.toDouble * 1000000000 / lasted}%6.0f MPS")
                lastTime = System.nanoTime()
              }
              at + 1
            }
            .mapConcat(_ => Nil)

        )

        HttpResponse(entity = "started!")
      case _: HttpRequest => HttpResponse(404, entity = "Unknown resource!")
    }, interface = "localhost", port = 9001)

    Await.result(binding, 1.second) // throws if binding fails
    println("Server online at http://localhost:9001")
    println("Press RETURN to stop...")
    StdIn.readLine()
  } finally {
    system.terminate()
  }

  ////////////// helpers //////////////

  lazy val index = HttpResponse(
    entity = HttpEntity(
      ContentTypes.`text/html(UTF-8)`,
      """|<html>
         | <body>
         |    <h1>Say hello to <i>akka-http-core</i>!</h1>
         |    <p>Defined resources:</p>
         |    <ul>
         |      <li><a href="/ping">/ping</a></li>
         |      <li><a href="/crash">/crash</a></li>
         |    </ul>
         |  </body>
         |</html>""".stripMargin))

  def echoWebSocketService: Flow[Message, Message, NotUsed] =
    Flow[Message] // just let message flow directly to the output

  def greeterWebSocketService: Flow[Message, Message, NotUsed] =
    Flow[Message]
      .collect {
        case TextMessage.Strict(name) => TextMessage(s"Hello '$name'")
        case tm: TextMessage          => TextMessage(Source.single("Hello ") ++ tm.textStream)
        // ignore binary messages
      }

  private lazy val fixedMessage = BinaryMessage.Strict(ByteString(new Array[Byte](500)))
  def sendDataWsService: Flow[Message, Message, NotUsed] =
    Flow.fromSinkAndSource(Sink.ignore, Source.repeat(fixedMessage))
}
