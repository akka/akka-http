package akka.http.scaladsl.impl.parsing

import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model2.HeadersFrame
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Sink, Source }
import akka.testkit.AkkaSpec
import akka.util.ByteString
import com.twitter.hpack.HexUtil
import org.scalatest.concurrent.ScalaFutures

class HeaderDecompressionSpec extends AkkaSpec with ScalaFutures {

  implicit val mat = ActorMaterializer()

  // test data from: https://github.com/twitter/hpack/blob/master/hpack/src/test/resources/hpack

  "HeaderDecompression" must {
    "decompress spec-example-1 to RawHeader" in {
      val headerBlock = "040c 2f73 616d 706c 652f 7061 7468".replace(" ", "")
      val headers = Map(":path" → "/sample/path")
      val onlyHeader = headers.head

      val bytes = ByteString(HexUtil.decodeHex(headerBlock.toCharArray))
      val frame = HeadersFrame(0, false, 1, 0, bytes)

      val decompressedHeaders =
        Source.single(frame)
          .via(new HeaderDecompression)
          .runWith(Sink.seq)
          .futureValue
      decompressedHeaders should contain(RawHeader(onlyHeader._1, onlyHeader._2))
    }

    // FIXME: should test data spanning over multiple Headers frames (to make sure we handle completion properly) 
  }

}
