/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.scaladsl

import docs.CompileOnlySpec
import org.scalatest.concurrent.ScalaFutures
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import akka.testkit.AkkaSpec

class HttpClientDecodingExampleSpec extends AkkaSpec with CompileOnlySpec with ScalaFutures {
  "single-request-decoding-example" in compileOnlySpec {
    //#single-request-decoding-example
    import akka.actor.ActorSystem
    import akka.http.scaladsl.Http
    import akka.http.scaladsl.coding.Coders
    import akka.http.scaladsl.model._, headers.HttpEncodings

    import scala.concurrent.Future

    implicit val system = ActorSystem()
    implicit val ec: ExecutionContext = system.dispatcher

    val http = Http()

    val requests: Seq[HttpRequest] = Seq(
      "https://httpbin.org/gzip", // Content-Encoding: gzip in response
      "https://httpbin.org/deflate", // Content-Encoding: deflate in response
      "https://httpbin.org/get" // no Content-Encoding in response
    ).map(uri => HttpRequest(uri = uri))

    def decodeResponse(response: HttpResponse): HttpResponse = {
      val decoder = response.encoding match {
        case HttpEncodings.gzip =>
          Coders.Gzip
        case HttpEncodings.deflate =>
          Coders.Deflate
        case HttpEncodings.identity =>
          Coders.NoCoding
        case other =>
          log.warning(s"Unknown encoding [$other], not decoding")
          Coders.NoCoding
      }

      decoder.decodeMessage(response)
    }

    val futureResponses: Future[Seq[HttpResponse]] =
      Future.traverse(requests)(http.singleRequest(_).map(decodeResponse))

    futureResponses.futureValue.foreach { resp =>
      system.log.info(s"response is ${resp.toStrict(1.second).futureValue}")
    }

    system.terminate()
    //#single-request-decoding-example
  }
}
