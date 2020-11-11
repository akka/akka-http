package akka.http.impl.engine.http2

import akka.actor.ActorSystem
import akka.http.scaladsl.ConnectionContext
import akka.http.scaladsl.model.ContentTypes
import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.HttpResponse
import akka.stream.SystemMaterializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.typesafe.config.ConfigFactory

import scala.concurrent.Future
import scala.concurrent.duration._

object Http2ServerWithPings {

  def main(args: Array[String]): Unit = {
    val config = ConfigFactory.parseString(
      """
         akka.loglevel = debug
         akka.http.server.preview.enable-http2 = on
         akka.http.server.http2.log-frames = true
         akka.http.server.http2.keepalive-time = 2s
         akka.http.server.http2.keepalive-timeout = 1s
         akka.http.server.http2.max-keepalives-without-data = 20
        """)

    implicit val system = ActorSystem("HttpServer", config)
    implicit val mat = SystemMaterializer(system).materializer

    val asyncHandler = { (req: HttpRequest) =>
      req.entity.discardBytes()
      Future.successful(HttpResponse(entity = HttpEntity.Chunked(
        ContentTypes.`text/plain(UTF-8)`,
        // slow stream to allow for some pinging inbetween
        Source.unfold(1L)(n => Some((n + 1, n))).map(n => HttpEntity.ChunkStreamPart(ByteString(n.toString)))
          .throttle(1, 5.seconds))
      ))
    }

    Http2().bindAndHandleAsync(asyncHandler, "localhost", 8080, ConnectionContext.noEncryption())
  }

}
