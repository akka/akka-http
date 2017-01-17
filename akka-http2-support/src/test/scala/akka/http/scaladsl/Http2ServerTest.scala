/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl

import java.nio.file.Paths

import akka.actor.ActorSystem
import akka.http.impl.util.ExampleHttpContexts
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{ CacheDirective, CacheDirectives }
import akka.http.scaladsl.model.http2.Http2PushRequestsHeader
import akka.stream._
import akka.stream.scaladsl.{ FileIO, Flow, Source }
import akka.util.ByteString
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.io.StdIn
import scala.util.Random

object Http2ServerTest extends App {
  val testConf: Config = ConfigFactory.parseString("""
    akka.loglevel = DEBUG
    akka.log-dead-letters = off
    akka.stream.materializer.debug.fuzzing-mode = off
    akka.actor.serialize-creators = off
    akka.actor.serialize-messages = off
    #akka.actor.default-dispatcher.throughput = 1000
    akka.actor.default-dispatcher.fork-join-executor.parallelism-max=8
                                                   """)
  implicit val system = ActorSystem("ServerTest", testConf)
  import system.dispatcher

  val settings = ActorMaterializerSettings(system)
    .withFuzzing(false)
    //    .withSyncProcessingLimit(Int.MaxValue)
    .withInputBuffer(128, 128)
  implicit val fm = ActorMaterializer(settings)

  def slowDown[T](millis: Int): T ⇒ Future[T] = { t ⇒
    akka.pattern.after(millis.millis, system.scheduler)(Future.successful(t))
  }

  val lastModifiedHeader = model.headers.`Last-Modified`(DateTime.now)

  val syncHandler: HttpRequest ⇒ HttpResponse = {
    case HttpRequest(GET, Uri.Path("/"), _, _, _)           ⇒ index
    case HttpRequest(GET, Uri.Path("/ping"), _, _, _)       ⇒ HttpResponse(entity = "PONG!")
    case HttpRequest(GET, Uri.Path("/image-page"), _, _, _) ⇒ imagePage
    case HttpRequest(GET, Uri(_, _, p, _, _), _, _, _) if p.toString.startsWith("/image1") ⇒
      HttpResponse(entity = HttpEntity(MediaTypes.`image/jpeg`, FileIO.fromPath(Paths.get("bigimage.jpg"), 100000).mapAsync(1)(slowDown(1))))
    case HttpRequest(GET, Uri(_, _, p, _, _), _, _, _) if p.toString.startsWith("/image2") ⇒
      HttpResponse(entity = HttpEntity(MediaTypes.`image/jpeg`, FileIO.fromPath(Paths.get("bigimage2.jpg"), 150000).mapAsync(1)(slowDown(2))))
    case HttpRequest(GET, Uri.Path("/crash"), _, _, _) ⇒ sys.error("BOOM!")
    case HttpRequest(GET, Uri.Path("/main.css"), _, _, _) ⇒
      HttpResponse(
        entity = "h1 { font-family: sans-serif; }",
        headers =
        lastModifiedHeader ::
          model.headers.Expires(DateTime.now.copy(day = 18)) ::
          model.headers.ETag("blubber") ::
          model.headers.`Cache-Control`(CacheDirectives.`max-age`(60), CacheDirectives.`public`) ::
          Nil
      ).mapEntity(_.transformDataBytes(addMegabyteOfBlanks))
    case _: HttpRequest ⇒ HttpResponse(404, entity = "Unknown resource!")
  }

  val asyncHandler: HttpRequest ⇒ Future[HttpResponse] =
    req ⇒ Future.successful(syncHandler(req))

  try {
    val bindings =
      for {
        binding1 ← Http().bindAndHandleAsync(asyncHandler, interface = "localhost", port = 9000, ExampleHttpContexts.exampleServerContext)
        binding2 ← Http2().bindAndHandleAsync(asyncHandler, interface = "localhost", port = 9001, ExampleHttpContexts.exampleServerContext)
      } yield (binding1, binding2)

    Await.result(bindings, 1.second) // throws if binding fails
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
         |  <head>
         |    <link rel="stylesheet" type="text/css" href="/main.css">
         |  </head>
         |  <body>
         |    <h1>Say hello to <i>akka-http-core</i>!</h1>
         |    <p>Defined resources:</p>
         |    <ul>
         |      <li><a href="/ping">/ping</a></li>
         |      <li><a href="/image-page">/image-page</a></li>
         |      <li><a href="/crash">/crash</a></li>
         |    </ul>
         |  </body>
         |</html>""".stripMargin), headers = Http2PushRequestsHeader(HttpRequest(uri = "https://localhost:9001/main.css")) :: Nil)

  def imagesBlock = {
    def one(): String =
      s"""<img width="80" height="60" src="/image1?cachebuster=${Random.nextInt}"></img>
         |<img width="80" height="60" src="/image2?cachebuster=${Random.nextInt}"></img>
         |""".stripMargin

    Seq.fill(3)(one()).mkString
  }

  lazy val thousandBlanks = ByteString(Array.fill(1000)(' '.toByte))
  lazy val addMegabyteOfBlanks =
    Flow[ByteString].concat(
      Source.fromIterator(() ⇒ Iterator.fill(1000)(thousandBlanks))
    //.mapAsync(1)(x ⇒ akka.pattern.after(10.millis, system.scheduler)(Future.successful(x)))
    )
  lazy val imagePage = HttpResponse(
    entity = HttpEntity(
      ContentTypes.`text/html(UTF-8)`,
      s"""|<html>
          |  <head>
          |    <link rel="stylesheet" type="text/css" href="/main.css">
          |  </head>
          |  <body>
          |    <h1>Image Page</h1>
          |    $imagesBlock
          |  </body>
          |</html>""".stripMargin).transformDataBytes(addMegabyteOfBlanks),
    headers = Http2PushRequestsHeader(HttpRequest(uri = "https://localhost:9001/main.css")) :: Nil)
}
