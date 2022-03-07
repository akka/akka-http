/*
 * Copyright (C) 2020-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.scaladsl

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ResponsePromise
import akka.http.scaladsl.model.headers.HttpEncodings
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.headers
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import com.typesafe.config.ConfigFactory

import scala.annotation.nowarn
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.concurrent.Promise

/** A small example app that shows how to use the HTTP/2 client API currently against actual internet servers */
object Http2ClientApp extends App {
  val config =
    ConfigFactory.parseString(
      """
         # akka.loglevel = debug
         akka.http.client.http2.log-frames = true
         akka.http.client.parsing.max-content-length = 20m
      """
    ).withFallback(ConfigFactory.defaultApplication())

  implicit val system = ActorSystem("Http2ClientApp", config)
  implicit val ec = system.dispatcher

  // #response-future-association
  val dispatch = singleRequest(Http().connectionTo("doc.akka.io").http2())

  dispatch(
    HttpRequest(
      uri = "https://doc.akka.io/api/akka/current/akka/actor/typed/scaladsl/index.html",
      headers = headers.`Accept-Encoding`(HttpEncodings.gzip) :: Nil)
  ).onComplete { res =>
      println(s"[1] Got index.html: $res")
      res.get.entity.dataBytes.runWith(Sink.ignore).onComplete(res => println(s"Finished reading [1] $res"))
    }

  // #response-future-association

  dispatch(
    HttpRequest(
      uri = "https://doc.akka.io/api/akka/current/index.js",
      headers = /*headers.`Accept-Encoding`(HttpEncodings.gzip) ::*/ Nil)
  ).onComplete { res =>
      println(s"[2] Got index.js: $res")
      res.get.entity.dataBytes.runWith(Sink.ignore).onComplete(res => println(s"Finished reading [2] $res"))
    }

  dispatch(HttpRequest(uri = "https://doc.akka.io/api/akka/current/lib/MaterialIcons-Regular.woff"))
    .flatMap(_.toStrict(1.second))
    .onComplete(res => println(s"[3] Got font: $res"))

  dispatch(HttpRequest(uri = "https://doc.akka.io/favicon.ico"))
    .flatMap(_.toStrict(1.second))
    .onComplete(res => println(s"[4] Got favicon: $res"))

  // OverflowStrategy.dropNew has been deprecated in latest Akka versions
  // FIXME: replace with 2.6 queue when 2.5 support is dropped
  @nowarn("msg=Use Source.queue") //
  //#response-future-association
  def singleRequest(connection: Flow[HttpRequest, HttpResponse, Any], bufferSize: Int = 100): HttpRequest => Future[HttpResponse] = {
    val queue =
      Source.queue(bufferSize, OverflowStrategy.dropNew)
        .via(connection)
        .to(Sink.foreach { response =>
          // complete the response promise with the response when it arrives
          val responseAssociation = response.attribute(ResponsePromise.Key).get
          responseAssociation.promise.trySuccess(response)
        })
        .run()

    req => {
      // create a promise of the response for each request and set it as an attribute on the request
      val p = Promise[HttpResponse]()
      queue.offer(req.addAttribute(ResponsePromise.Key, ResponsePromise(p)))
        // return the future response
        .flatMap(_ => p.future)
    }
  }
  //#response-future-association

}

