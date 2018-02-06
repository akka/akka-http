/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.parsing

import scala.concurrent.Await
import scala.concurrent.duration._

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._
import akka.http.scaladsl._
import akka.http.scaladsl.model._

import org.scalatest._
import akka.testkit._

class HttpsConnectionToHttpServerSpec extends AkkaSpec() {
  "A HTTP server" should {
    "log a nice error when connecting with HTTPs instead of HTTP" in {
      implicit val materializer = ActorMaterializer()

      val flow = Flow.fromFunction((_: HttpRequest) ⇒ ???)

      val binding = Http().bindAndHandle(flow, "127.0.0.1", port = 0).futureValue
      val uri = "https://" + binding.localAddress.getHostString + ":" + binding.localAddress.getPort
      EventFilter.warning(pattern = "Perhaps this was an HTTPS request sent to an HTTP endpoint", occurrences = 6) intercept {
        Await.ready(Http().singleRequest(HttpRequest(uri = uri)), 10.seconds)
      }

      Await.result(binding.unbind(), 10.seconds)
    }
  }
}
