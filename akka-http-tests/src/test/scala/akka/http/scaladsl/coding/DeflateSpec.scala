/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.coding

import akka.util.ByteString
import java.io.{ InputStream, OutputStream }
import java.util.zip._

import akka.http.scaladsl.model.HttpMethods.POST
import akka.http.scaladsl.model.{ HttpEntity, HttpRequest }
import akka.http.impl.util._
import akka.http.scaladsl.model.headers.{ HttpEncodings, `Content-Encoding` }
import akka.stream.scaladsl.Flow
import akka.testkit._

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

class DeflateSpec extends CoderSpec {
  protected def Coder: Coder with StreamDecoder = Deflate

  protected def newDecodedInputStream(underlying: InputStream): InputStream =
    new InflaterInputStream(underlying)

  protected def newEncodedOutputStream(underlying: OutputStream): OutputStream =
    new DeflaterOutputStream(underlying)

  override def extraTests(): Unit = {
    "throw early if header is corrupt" in {
      (the[RuntimeException] thrownBy {
        ourDecode(ByteString(0, 1, 2, 3, 4))
      }).ultimateCause should be(a[DataFormatException])
    }
    "properly round-trip encode/decode an HttpRequest using wrapping" in {
      val request = HttpRequest(POST, entity = HttpEntity(largeText))
      Deflate.decodeMessage(encodeNoWrappedMessage(request), noWrap = true).toStrict(3.seconds.dilated)
        .awaitResult(3.seconds.dilated) should equal(request)
    }
  }

  private def encodeNoWrappedMessage(request: HttpRequest): HttpRequest = {
    val deflaterWithoutWrapping = new Deflate(Encoder.DefaultFilter) {
      override def newCompressor = new DeflateCompressor {
        override lazy val deflater = new Deflater(Deflater.BEST_COMPRESSION, true)
      }
    }
    request.transformEntityDataBytes(deflaterWithoutWrapping.encoderFlow)
      .withHeaders(`Content-Encoding`(HttpEncodings.deflate) +: request.headers)
  }
}
