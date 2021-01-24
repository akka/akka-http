/*
 * Copyright (C) 2009-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.model

import java.util.concurrent.TimeoutException

import akka.NotUsed
import akka.http.impl.util._
import akka.http.scaladsl.model.HttpEntity._
import akka.http.scaladsl.model.headers.RawHeader
import akka.stream.scaladsl._
import akka.testkit._
import akka.util.ByteString
import org.scalatest.matchers.{ MatchResult, Matcher }

import scala.concurrent.duration._
import scala.concurrent.{ Await, Promise }

class HttpEntitySpec extends AkkaSpecWithMaterializer {
  val tpe: ContentType = ContentTypes.`application/octet-stream`
  val abc = ByteString("abc")
  val de = ByteString("de")
  val fgh = ByteString("fgh")
  val ijk = ByteString("ijk")

  val awaitAtMost = 3.seconds.dilated

  "HttpEntity" should {
    "support dataBytes" should {
      "Strict" in {
        Strict(tpe, abc) should collectBytesTo(abc)
      }
      "Default" in {
        Default(tpe, 11, source(abc, de, fgh, ijk)) should collectBytesTo(abc, de, fgh, ijk)
      }
      "CloseDelimited" in {
        CloseDelimited(tpe, source(abc, de, fgh, ijk)) should collectBytesTo(abc, de, fgh, ijk)
      }
      "Chunked w/o LastChunk" in {
        Chunked(tpe, source(Chunk(abc), Chunk(fgh), Chunk(ijk))) should collectBytesTo(abc, fgh, ijk)
      }
      "Chunked with LastChunk" in {
        Chunked(tpe, source(Chunk(abc), Chunk(fgh), Chunk(ijk), LastChunk)) should collectBytesTo(abc, fgh, ijk)
      }
    }
    "support contentLength" should {
      "Strict" in {
        Strict(tpe, abc).contentLengthOption shouldEqual Some(3)
      }
      "Default" in {
        Default(tpe, 11, source(abc, de, fgh, ijk)).contentLengthOption shouldEqual Some(11)
      }
      "CloseDelimited" in {
        CloseDelimited(tpe, source(abc, de, fgh, ijk)).contentLengthOption shouldEqual None
      }
      "Chunked" in {
        Chunked(tpe, source(Chunk(abc), Chunk(fgh), Chunk(ijk))).contentLengthOption shouldEqual None
      }
    }
    "support toStrict" should {
      "Strict" in {
        Strict(tpe, abc) should strictifyTo(Strict(tpe, abc))
      }
      "Default" in {
        Default(tpe, 11, source(abc, de, fgh, ijk)) should
          strictifyTo(Strict(tpe, abc ++ de ++ fgh ++ ijk))
      }
      "CloseDelimited" in {
        CloseDelimited(tpe, source(abc, de, fgh, ijk)) should
          strictifyTo(Strict(tpe, abc ++ de ++ fgh ++ ijk))
      }
      "Chunked w/o LastChunk" in {
        Chunked(tpe, source(Chunk(abc), Chunk(fgh), Chunk(ijk))) should
          strictifyTo(Strict(tpe, abc ++ fgh ++ ijk))
      }
      "Chunked with LastChunk" in {
        Chunked(tpe, source(Chunk(abc), Chunk(fgh), Chunk(ijk), LastChunk)) should
          strictifyTo(Strict(tpe, abc ++ fgh ++ ijk))
      }
      "Infinite data stream" in {
        val neverCompleted = Promise[ByteString]()
        intercept[TimeoutException] {
          Await.result(Default(tpe, 42, Source.fromFuture(neverCompleted.future)).toStrict(100.millis), awaitAtMost)
        }.getMessage should be("HttpEntity.toStrict timed out after 100 milliseconds while still waiting for outstanding data")
      }
    }
    "support toStrict with the default max size" should {
      "Infinite data stream" in {
        intercept[EntityStreamException] {
          Await.result(Chunked(tpe, Source.repeat(Chunk(abc))).toStrict(awaitAtMost), awaitAtMost)
        }.getMessage should be("Request too large: Request was longer than the maximum of 8388608")
      }
    }
    "support toStrict with a max size" should {
      "Strict" in {
        intercept[EntityStreamException] {
          Await.result(Strict(tpe, abc).toStrict(awaitAtMost, maxBytes = 1), awaitAtMost)
        }.getMessage should be("Request too large: Request of size 3 was longer than the maximum of 1")
      }
      "Default" in {
        intercept[EntityStreamException] {
          Await.result(Default(tpe, 11, source(abc, de, fgh, ijk)).toStrict(awaitAtMost, maxBytes = 1), awaitAtMost)
        }.getMessage should be("Request too large: Request of size 11 was longer than the maximum of 1")
      }
      "CloseDelimited" in {
        intercept[EntityStreamException] {
          Await.result(CloseDelimited(tpe, source(abc, de, fgh, ijk)).toStrict(awaitAtMost, maxBytes = 1), awaitAtMost)
        }.getMessage should be("Request too large: Request was longer than the maximum of 1")
      }
      "Chunked w/o LastChunk" in {
        intercept[EntityStreamException] {
          Await.result(Chunked(tpe, source(Chunk(abc), Chunk(fgh), Chunk(ijk))).toStrict(awaitAtMost, maxBytes = 1), awaitAtMost)
        }.getMessage should be("Request too large: Request was longer than the maximum of 1")
      }
      "Chunked with LastChunk" in {
        intercept[EntityStreamException] {
          Await.result(Chunked(tpe, source(Chunk(abc), Chunk(fgh), Chunk(ijk), LastChunk)).toStrict(awaitAtMost, maxBytes = 1), awaitAtMost)
        }.getMessage should be("Request too large: Request was longer than the maximum of 1")
      }
      "Infinite data stream" in {
        intercept[EntityStreamException] {
          Await.result(Chunked(tpe, Source.repeat(Chunk(abc))).toStrict(awaitAtMost, maxBytes = 1), awaitAtMost)
        }.getMessage should be("Request too large: Request was longer than the maximum of 1")
      }
    }
    "support transformDataBytes" should {
      "Strict" in {
        Strict(tpe, abc) should transformTo(Strict(tpe, doubleChars("abc") ++ trailer))
      }
      "Default" in {
        Default(tpe, 11, source(abc, de, fgh, ijk)) should
          transformTo(Strict(tpe, doubleChars("abcdefghijk") ++ trailer))
      }
      "CloseDelimited" in {
        CloseDelimited(tpe, source(abc, de, fgh, ijk)) should
          transformTo(Strict(tpe, doubleChars("abcdefghijk") ++ trailer))
      }
      "Chunked w/o LastChunk" in {
        Chunked(tpe, source(Chunk(abc), Chunk(fgh), Chunk(ijk))) should
          transformTo(Strict(tpe, doubleChars("abcfghijk") ++ trailer))
      }
      "Chunked with LastChunk" in {
        Chunked(tpe, source(Chunk(abc), Chunk(fgh), Chunk(ijk), LastChunk)) should
          transformTo(Strict(tpe, doubleChars("abcfghijk") ++ trailer))
      }
      "Chunked with extra LastChunk" in {
        Chunked(tpe, source(Chunk(abc), Chunk(fgh), Chunk(ijk), LastChunk, LastChunk)) should
          transformTo(Strict(tpe, doubleChars("abcfghijk") ++ trailer))
      }
      "Chunked with LastChunk with trailer header" in {
        Chunked(tpe, source(Chunk(abc), Chunk(fgh), Chunk(ijk), LastChunk("", RawHeader("Foo", "pip apo") :: Nil))) should
          transformTo(Strict(tpe, doubleChars("abcfghijk") ++ trailer))
      }
      "Chunked with LastChunk with trailer header keep header chunk" in {
        val entity = Chunked(tpe, source(Chunk(abc), Chunk(fgh), Chunk(ijk), LastChunk("", RawHeader("Foo", "pip apo") :: Nil)))
        val transformed = entity.transformDataBytes(duplicateBytesTransformer)
        val parts = transformed.chunks.runWith(Sink.seq).awaitResult(100.millis)

        parts.map(_.data).reduce(_ ++ _) shouldEqual doubleChars("abcfghijk") ++ trailer

        val lastPart = parts.last
        lastPart.isLastChunk shouldBe (true)
        lastPart shouldBe a[LastChunk]
        lastPart.asInstanceOf[LastChunk].trailer shouldEqual (RawHeader("Foo", "pip apo") :: Nil)
      }
    }
    "support toString" should {
      "Strict with binary MediaType" in {
        val binaryType = ContentTypes.`application/octet-stream`
        val entity = Strict(binaryType, abc)
        entity should renderStrictDataAs("3 bytes total")
      }
      "Strict with non-binary MediaType" in {
        val nonBinaryType = ContentTypes.`application/json`
        val entity = Strict(nonBinaryType, abc)
        entity should renderStrictDataAs("3 bytes total")
      }
      "Default" in {
        val entity = Default(tpe, 11, source(abc, de, fgh, ijk))
        entity.toString should include(entity.productPrefix)
        entity.toString should include("11")
        entity.toString shouldNot include("Source")
      }
      "CloseDelimited" in {
        val entity = CloseDelimited(tpe, source(abc, de, fgh, ijk))
        entity.toString should include(entity.productPrefix)
        entity.toString shouldNot include("Source")
      }
      "Chunked" in {
        val entity = Chunked(tpe, source(Chunk(abc)))
        entity.toString should include(entity.productPrefix)
        entity.toString shouldNot include("Source")
      }
      "IndefiniteLength" in {
        val entity = IndefiniteLength(tpe, source(abc, de, fgh, ijk))
        entity.toString should include(entity.productPrefix)
        entity.toString shouldNot include("Source")
      }
    }
    "support withoutSizeLimit" should {
      "Strict" in {
        HttpEntity.Empty.withoutSizeLimit
        withReturnType[UniversalEntity](Strict(tpe, abc).withoutSizeLimit)
        withReturnType[RequestEntity](Strict(tpe, abc).asInstanceOf[RequestEntity].withoutSizeLimit)
        withReturnType[ResponseEntity](Strict(tpe, abc).asInstanceOf[ResponseEntity].withoutSizeLimit)
        withReturnType[HttpEntity](Strict(tpe, abc).asInstanceOf[HttpEntity].withoutSizeLimit)
      }
      "Default" in {
        withReturnType[Default](Default(tpe, 11, source(abc, de, fgh, ijk)).withoutSizeLimit)
        withReturnType[RequestEntity](Default(tpe, 11, source(abc, de, fgh, ijk)).asInstanceOf[RequestEntity].withoutSizeLimit)
        withReturnType[ResponseEntity](Default(tpe, 11, source(abc, de, fgh, ijk)).asInstanceOf[ResponseEntity].withoutSizeLimit)
        withReturnType[HttpEntity](Default(tpe, 11, source(abc, de, fgh, ijk)).asInstanceOf[HttpEntity].withoutSizeLimit)
      }
      "CloseDelimited" in {
        withReturnType[CloseDelimited](CloseDelimited(tpe, source(abc, de, fgh, ijk)).withoutSizeLimit)
        withReturnType[ResponseEntity](CloseDelimited(tpe, source(abc, de, fgh, ijk)).asInstanceOf[ResponseEntity].withoutSizeLimit)
        withReturnType[HttpEntity](CloseDelimited(tpe, source(abc, de, fgh, ijk)).asInstanceOf[HttpEntity].withoutSizeLimit)
      }
      "Chunked" in {
        withReturnType[Chunked](Chunked(tpe, source(Chunk(abc), Chunk(fgh), Chunk(ijk), LastChunk)).withoutSizeLimit)
        withReturnType[RequestEntity](Chunked(tpe, source(Chunk(abc), Chunk(fgh), Chunk(ijk), LastChunk)).asInstanceOf[RequestEntity].withoutSizeLimit)
        withReturnType[ResponseEntity](Chunked(tpe, source(Chunk(abc), Chunk(fgh), Chunk(ijk), LastChunk)).asInstanceOf[ResponseEntity].withoutSizeLimit)
        withReturnType[HttpEntity](Chunked(tpe, source(Chunk(abc), Chunk(fgh), Chunk(ijk), LastChunk)).asInstanceOf[HttpEntity].withoutSizeLimit)
      }
    }
    "support withSizeLimit" should {
      "Strict" in {
        HttpEntity.Empty.withSizeLimit(123L)
        withReturnType[UniversalEntity](Strict(tpe, abc).withSizeLimit(123L))
        withReturnType[RequestEntity](Strict(tpe, abc).asInstanceOf[RequestEntity].withSizeLimit(123L))
        withReturnType[ResponseEntity](Strict(tpe, abc).asInstanceOf[ResponseEntity].withSizeLimit(123L))
        withReturnType[HttpEntity](Strict(tpe, abc).asInstanceOf[HttpEntity].withSizeLimit(123L))
      }
      "Default" in {
        withReturnType[Default](Default(tpe, 11, source(abc, de, fgh, ijk)).withoutSizeLimit)
        withReturnType[RequestEntity](Default(tpe, 11, source(abc, de, fgh, ijk)).asInstanceOf[RequestEntity].withSizeLimit(123L))
        withReturnType[ResponseEntity](Default(tpe, 11, source(abc, de, fgh, ijk)).asInstanceOf[ResponseEntity].withSizeLimit(123L))
        withReturnType[HttpEntity](Default(tpe, 11, source(abc, de, fgh, ijk)).asInstanceOf[HttpEntity].withSizeLimit(123L))
      }
      "CloseDelimited" in {
        withReturnType[CloseDelimited](CloseDelimited(tpe, source(abc, de, fgh, ijk)).withSizeLimit(123L))
        withReturnType[ResponseEntity](CloseDelimited(tpe, source(abc, de, fgh, ijk)).asInstanceOf[ResponseEntity].withSizeLimit(123L))
        withReturnType[HttpEntity](CloseDelimited(tpe, source(abc, de, fgh, ijk)).asInstanceOf[HttpEntity].withSizeLimit(123L))
      }
      "Chunked" in {
        withReturnType[Chunked](Chunked(tpe, source(Chunk(abc), Chunk(fgh), Chunk(ijk), LastChunk)).withSizeLimit(123L))
        withReturnType[RequestEntity](Chunked(tpe, source(Chunk(abc), Chunk(fgh), Chunk(ijk), LastChunk)).asInstanceOf[RequestEntity].withSizeLimit(123L))
        withReturnType[ResponseEntity](Chunked(tpe, source(Chunk(abc), Chunk(fgh), Chunk(ijk), LastChunk)).asInstanceOf[ResponseEntity].withSizeLimit(123L))
        withReturnType[HttpEntity](Chunked(tpe, source(Chunk(abc), Chunk(fgh), Chunk(ijk), LastChunk)).asInstanceOf[HttpEntity].withSizeLimit(123L))
      }
    }
  }

  def source[T](elems: T*) = Source(elems.toList)

  def collectBytesTo(bytes: ByteString*): Matcher[HttpEntity] =
    equal(bytes.toVector).matcher[Seq[ByteString]].compose { entity =>
      val future = entity.dataBytes.limit(1000).runWith(Sink.seq)
      Await.result(future, awaitAtMost)
    }

  def withReturnType[T](expr: T) = expr

  def strictifyTo(strict: Strict): Matcher[HttpEntity] =
    equal(strict).matcher[Strict].compose(x => Await.result(x.toStrict(awaitAtMost), awaitAtMost))

  def transformTo(strict: Strict): Matcher[HttpEntity] =
    equal(strict).matcher[Strict].compose { x =>
      val transformed = x.transformDataBytes(duplicateBytesTransformer)
      Await.result(transformed.toStrict(awaitAtMost), awaitAtMost)
    }

  def renderStrictDataAs(dataRendering: String): Matcher[Strict] =
    Matcher { strict: Strict =>
      val expectedRendering = s"${strict.productPrefix}(${strict.contentType},$dataRendering)"
      MatchResult(
        strict.toString == expectedRendering,
        strict.toString + " != " + expectedRendering,
        strict.toString + " == " + expectedRendering)
    }

  def duplicateBytesTransformer: Flow[ByteString, ByteString, NotUsed] =
    Flow[ByteString].via(StreamUtils.byteStringTransformer(doubleChars, () => trailer))

  def trailer: ByteString = ByteString("--dup")
  def doubleChars(bs: ByteString): ByteString = ByteString(bs.flatMap(b => Seq(b, b)): _*)
  def doubleChars(str: String): ByteString = doubleChars(ByteString(str))
}
