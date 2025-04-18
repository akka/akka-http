/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.ws

import akka.NotUsed

import scala.concurrent.Await
import scala.concurrent.duration._
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.AttributeKeys.webSocketUpgrade
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model.ws.Message
import akka.stream.scaladsl.Flow

import scala.io.StdIn

object WSServerAutobahnTest extends App {
  implicit val system: ActorSystem = ActorSystem("WSServerTest")

  val host = System.getProperty("akka.ws-host", "127.0.0.1")
  val port = System.getProperty("akka.ws-port", "9001").toInt
  val mode = System.getProperty("akka.ws-mode", "read") // read or sleep

  try {
    val binding = Http().newServerAt(host, port).bindSync({
      case req @ HttpRequest(GET, Uri.Path("/"), _, _, _) if req.attribute(webSocketUpgrade).isDefined =>
        req.attribute(webSocketUpgrade) match {
          case Some(upgrade) => upgrade.handleMessages(echoWebSocketService) // needed for running the autobahn test suite
          case None          => HttpResponse(400, entity = "Not a valid websocket request!")
        }
      case _: HttpRequest => HttpResponse(404, entity = "Unknown resource!")
    })

    Await.result(binding, 3.second) // throws if binding fails
    println(s"Server online at http://$host:$port")
    mode match {
      case "sleep" => while (true) Thread.sleep(1.minute.toMillis)
      case "read"  => StdIn.readLine("Press RETURN to stop...")
      case _       => throw new Exception("akka.ws-mode MUST be sleep or read.")
    }
  } finally {
    system.terminate()
  }

  def echoWebSocketService: Flow[Message, Message, NotUsed] =
    Flow[Message] // just let message flow directly to the output
}
