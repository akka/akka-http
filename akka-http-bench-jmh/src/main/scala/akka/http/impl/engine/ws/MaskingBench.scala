package akka.http.impl.engine.ws

import org.openjdk.jmh.annotations.Benchmark

import akka.util.ByteString
import akka.http.CommonBenchmark

class MaskingBench extends CommonBenchmark {
  val data = ByteString(new Array[Byte](10000))
  val mask = 0xfedcba09

  @Benchmark
  def benchRequestProcessing(): (ByteString, Int) =
    FrameEventParser.mask(data, mask)
}
