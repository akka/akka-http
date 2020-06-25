/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.scaladsl.server.directives

import akka.http.scaladsl.coding.{ Coder, Coders, Gzip }
import akka.http.scaladsl.model.{ HttpEntity, HttpResponse }
import akka.http.scaladsl.model.headers.{ HttpEncoding, HttpEncodings, `Accept-Encoding`, `Content-Encoding` }
import akka.http.scaladsl.model.headers.HttpEncodings._
import akka.http.scaladsl.model.MediaTypes._
import akka.http.scaladsl.server._
import akka.util.ByteString
import com.github.ghik.silencer.silent
import docs.CompileOnlySpec
import org.scalatest.matchers.Matcher

class CodingDirectivesExamplesSpec extends RoutingSpec with CompileOnlySpec {
  "responseEncodingAccepted" in {
    //#responseEncodingAccepted
    val route = responseEncodingAccepted(gzip) { complete("content") }

    Get("/") ~> route ~> check {
      responseAs[String] shouldEqual "content"
    }
    Get("/") ~> `Accept-Encoding`(deflate) ~> route ~> check {
      rejection shouldEqual UnacceptedResponseEncodingRejection(gzip)
    }
    //#responseEncodingAccepted
  }
  "encodeResponse" in {
    //#encodeResponse
    val route = encodeResponse { complete("content") }

    // tests:
    Get("/") ~> route ~> check {
      response should haveContentEncoding(identity)
    }
    Get("/") ~> `Accept-Encoding`(gzip, deflate) ~> route ~> check {
      response should haveContentEncoding(gzip)
    }
    Get("/") ~> `Accept-Encoding`(deflate) ~> route ~> check {
      response should haveContentEncoding(deflate)
    }
    Get("/") ~> `Accept-Encoding`(identity) ~> route ~> check {
      response should haveContentEncoding(identity)
    }
    //#encodeResponse
  }
  "encodeResponseWith" in {
    //#encodeResponseWith
    val route = encodeResponseWith(Coders.Gzip) { complete("content") }

    // tests:
    Get("/") ~> route ~> check {
      response should haveContentEncoding(gzip)
    }
    Get("/") ~> `Accept-Encoding`(gzip, deflate) ~> route ~> check {
      response should haveContentEncoding(gzip)
    }
    Get("/") ~> `Accept-Encoding`(deflate) ~> route ~> check {
      rejection shouldEqual UnacceptedResponseEncodingRejection(gzip)
    }
    Get("/") ~> `Accept-Encoding`(identity) ~> route ~> check {
      rejection shouldEqual UnacceptedResponseEncodingRejection(gzip)
    }

    // with custom compression level:
    val routeWithLevel9 = encodeResponseWith(Coders.Gzip(compressionLevel = 9)) { complete("content") }
    Get("/") ~> routeWithLevel9 ~> check {
      response should haveContentEncoding(gzip)
    }
    //#encodeResponseWith
  }

  val helloGzipped = compress("Hello", Coders.Gzip)
  val helloDeflated = compress("Hello", Coders.Deflate)
  "decodeRequest" in {
    //#decodeRequest
    val route =
      decodeRequest {
        entity(as[String]) { content: String =>
          complete(s"Request content: '$content'")
        }
      }

    // tests:
    Post("/", helloGzipped) ~> `Content-Encoding`(gzip) ~> route ~> check {
      responseAs[String] shouldEqual "Request content: 'Hello'"
    }
    Post("/", helloDeflated) ~> `Content-Encoding`(deflate) ~> route ~> check {
      responseAs[String] shouldEqual "Request content: 'Hello'"
    }
    Post("/", "hello uncompressed") ~> `Content-Encoding`(identity) ~> route ~> check {
      responseAs[String] shouldEqual "Request content: 'hello uncompressed'"
    }
    //#decodeRequest
  }
  "decodeRequestWith-0" in {
    //#decodeRequestWith
    val route =
      decodeRequestWith(Coders.Gzip) {
        entity(as[String]) { content: String =>
          complete(s"Request content: '$content'")
        }
      }

    // tests:
    Post("/", helloGzipped) ~> `Content-Encoding`(gzip) ~> route ~> check {
      responseAs[String] shouldEqual "Request content: 'Hello'"
    }
    Post("/", helloDeflated) ~> `Content-Encoding`(deflate) ~> route ~> check {
      rejection shouldEqual UnsupportedRequestEncodingRejection(gzip)
    }
    Post("/", "hello") ~> `Content-Encoding`(identity) ~> route ~> check {
      rejection shouldEqual UnsupportedRequestEncodingRejection(gzip)
    }
    //#decodeRequestWith
  }
  "decodeRequestWith-1" in {
    //#decodeRequestWith
    val route =
      decodeRequestWith(Coders.Gzip, Coders.NoCoding) {
        entity(as[String]) { content: String =>
          complete(s"Request content: '$content'")
        }
      }

    // tests:
    Post("/", helloGzipped) ~> `Content-Encoding`(gzip) ~> route ~> check {
      responseAs[String] shouldEqual "Request content: 'Hello'"
    }
    Post("/", helloDeflated) ~> `Content-Encoding`(deflate) ~> route ~> check {
      rejections shouldEqual List(UnsupportedRequestEncodingRejection(gzip), UnsupportedRequestEncodingRejection(identity))
    }
    Post("/", "hello uncompressed") ~> `Content-Encoding`(identity) ~> route ~> check {
      responseAs[String] shouldEqual "Request content: 'hello uncompressed'"
    }
    //#decodeRequestWith
  }

  "withPrecompressedMediaTypeSupport" in {
    //#withPrecompressedMediaTypeSupport
    val svgz = compress("<svg/>", Coders.Gzip)
    val route =
      withPrecompressedMediaTypeSupport {
        complete(HttpResponse(entity = HttpEntity(`image/svgz`, svgz)))
      }

    // tests:
    Get("/") ~> route ~> check {
      header[`Content-Encoding`] shouldEqual Some(`Content-Encoding`(gzip))
      mediaType shouldEqual `image/svg+xml`
    }
    //#withPrecompressedMediaTypeSupport
  }

  def haveContentEncoding(encoding: HttpEncoding): Matcher[HttpResponse] =
    be(encoding) compose { (_: HttpResponse).header[`Content-Encoding`].map(_.encodings.head).getOrElse(HttpEncodings.identity) }

  @silent("encode in trait Encoder is deprecated")
  def compress(input: String, encoder: Coder): ByteString = encoder.encode(ByteString(input))
}
