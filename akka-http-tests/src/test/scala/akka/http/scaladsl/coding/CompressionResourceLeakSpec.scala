/*
 * Copyright (C) 2009-2025 Lightbend Inc. <https://akka.io>
 */

package akka.http.scaladsl.coding

import java.util.zip.{ Deflater, Inflater }

import akka.http.impl.util._
import akka.stream.scaladsl.{ Flow, Keep, Sink, Source }
import akka.stream.testkit.scaladsl.{ TestSink, TestSource }
import akka.testkit._
import akka.util.ByteString
import org.scalatest.Inspectors
import org.scalatest.concurrent.Eventually
import org.scalatest.wordspec.AnyWordSpec

import scala.annotation.nowarn
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._

/**
 * Reproduces a resource leak: java.util.zip.Inflater/Deflater instances used by the gzip/deflate
 * (de)compression stages wrap native zlib memory that must be released explicitly via `.end()`.
 * That only happens today on the normal-completion path (upstream finish / encoder finish()).
 * If the stream is cancelled from downstream (e.g. a client disconnects mid-response) before
 * that happens, the Inflater/Deflater is never ended.
 *
 * These tests drive the stages via TestSource/TestSink so that we can cancel the stream from
 * downstream *before* upstream ever completes, and then assert that the underlying Inflater/
 * Deflater was released. A released instance throws IllegalStateException("... has been closed")
 * from any subsequent call (verified here via `reset()`), so that's used as the release check.
 */
@nowarn("msg=deprecated .* is internal API")
class CompressionResourceLeakSpec extends AnyWordSpec with CodecSpecSupport with Eventually with Inspectors {
  "GzipDecompressor" should {
    "end its Inflater when the decode stream is cancelled before upstream finishes" in {
      var captured: Inflater = null
      class ProbeGzipDecompressor extends GzipDecompressor() {
        override protected def newInflater(): Inflater = {
          val inflater = super.newInflater()
          captured = inflater
          inflater
        }
      }

      val compressed = new GzipCompressor().compressAndFinish(smallTextBytes)

      val (pub, sub) = TestSource.probe[ByteString]
        .via(Flow.fromGraph(new ProbeGzipDecompressor))
        .toMat(TestSink.probe[ByteString])(Keep.both)
        .run()

      sub.request(1)
      pub.sendNext(compressed)
      sub.expectNext(2.seconds.dilated)
      sub.cancel()

      eventually { captured should not be null }
      eventually { an[IllegalStateException] should be thrownBy captured.reset() }
    }
  }

  "DeflateDecompressor" should {
    "end its Inflater when the decode stream is cancelled before upstream finishes" in {
      var captured: Inflater = null
      class ProbeDeflateDecompressor extends DeflateDecompressor() {
        override protected def newInflater(wrapped: Boolean): Inflater = {
          val inflater = super.newInflater(wrapped)
          captured = inflater
          inflater
        }
      }

      val compressed = new DeflateCompressor().compressAndFinish(smallTextBytes)

      val (pub, sub) = TestSource.probe[ByteString]
        .via(Flow.fromGraph(new ProbeDeflateDecompressor))
        .toMat(TestSink.probe[ByteString])(Keep.both)
        .run()

      sub.request(1)
      pub.sendNext(compressed)
      sub.expectNext(2.seconds.dilated)
      sub.cancel()

      eventually { captured should not be null }
      eventually { an[IllegalStateException] should be thrownBy captured.reset() }
    }

    "end every Inflater it creates, not just the last one, when decoding concatenated deflate blocks" in {
      val captured = ArrayBuffer.empty[Inflater]
      class ProbeDeflateDecompressor extends DeflateDecompressor() {
        override protected def newInflater(wrapped: Boolean): Inflater = {
          val inflater = super.newInflater(wrapped)
          captured += inflater
          inflater
        }
      }

      // two independently-finished raw deflate blocks concatenated into a single stream: the decompressor
      // creates a fresh Inflater for each block (see ProbeWrapping), so this must produce (at least) two
      val concatenated =
        new DeflateCompressor().compressAndFinish(smallTextBytes) ++
          new DeflateCompressor().compressAndFinish(largeTextBytes)

      Source.single(concatenated)
        .via(Flow.fromGraph(new ProbeDeflateDecompressor))
        .runWith(Sink.ignore)
        .awaitResult(3.seconds.dilated)

      captured.size should be > 1
      forAll(captured) { inflater =>
        an[IllegalStateException] should be thrownBy inflater.reset()
      }
    }
  }

  "the gzip Encoder" should {
    "end its Deflater when the encode stream is cancelled before upstream finishes" in {
      class ProbeGzipCompressor extends GzipCompressor {
        def currentDeflater: Deflater = deflater
      }
      var captured: ProbeGzipCompressor = null
      val probeEncoder = new Gzip(GzipCompressor.DefaultCompressionLevel, Encoder.DefaultFilter) {
        override def newCompressor: ProbeGzipCompressor = {
          val c = new ProbeGzipCompressor
          captured = c
          c
        }
      }

      val (pub, sub) = TestSource.probe[ByteString]
        .via(probeEncoder.encoderFlow)
        .toMat(TestSink.probe[ByteString])(Keep.both)
        .run()

      sub.request(1)
      pub.sendNext(smallTextBytes)
      sub.expectNext(2.seconds.dilated)
      sub.cancel()

      eventually { captured should not be null }
      eventually { an[IllegalStateException] should be thrownBy captured.currentDeflater.reset() }
    }
  }
}
