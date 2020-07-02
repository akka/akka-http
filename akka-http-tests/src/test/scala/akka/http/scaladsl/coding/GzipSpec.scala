/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.coding

import java.io.{ InputStream, OutputStream }
import java.util.zip.{ GZIPInputStream, GZIPOutputStream, ZipException }

import akka.http.impl.util._
import akka.util.ByteString
import com.github.ghik.silencer.silent

@silent("deprecated .* is internal API")
class GzipSpec extends CoderSpec {
  protected def Coder: Coder = Coders.Gzip(compressionLevel = 9)

  protected def newDecodedInputStream(underlying: InputStream): InputStream =
    new GZIPInputStream(underlying)

  protected def newEncodedOutputStream(underlying: OutputStream): OutputStream =
    new GZIPOutputStream(underlying)

  override def extraTests(): Unit = {
    "decode concatenated compressions" in {
      ourDecode(Seq(encode("Hello, "), encode("dear "), encode("User!")).join) should readAs("Hello, dear User!")
    }
    "provide a better compression ratio than the standard Gzip/Gunzip streams" in pendingUntilFixed {
      // for this input, it seems gzip level 5-9 provide almost the same compression, so < is too strict
      // TODO: find better input where level makes a more significant difference
      ourEncode(largeTextBytes).length should be < streamEncode(largeTextBytes).length
    }
    "throw an error on truncated input" in {
      val ex = the[RuntimeException] thrownBy ourDecode(streamEncode(smallTextBytes).dropRight(5))
      ex.ultimateCause.getMessage should equal("Truncated GZIP stream")
    }
    "throw an error if compressed data is just missing the trailer at the end" in {
      def brokenCompress(payload: String) = Coders.Gzip.newCompressor.compress(ByteString(payload, "UTF-8"))
      val ex = the[RuntimeException] thrownBy ourDecode(brokenCompress("abcdefghijkl"))
      ex.ultimateCause.getMessage should equal("Truncated GZIP stream")
    }
    "throw early if header is corrupt" in {
      val cause = (the[RuntimeException] thrownBy ourDecode(ByteString(0, 1, 2, 3, 4))).ultimateCause
      cause should (be(a[ZipException]) and have message "Not in GZIP format")
    }
  }
}
