/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.coding

import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.HttpEncodings

class Deflate private (compressionLevel: Int, val messageFilter: HttpMessage => Boolean) extends Coder with StreamDecoder {
  def this(messageFilter: HttpMessage => Boolean) = {
    this(DeflateCompressor.DefaultCompressionLevel, messageFilter)
  }

  val encoding = HttpEncodings.deflate
  def newCompressor = new DeflateCompressor(compressionLevel)
  def newDecompressorStage(maxBytesPerChunk: Int) = () => new DeflateDecompressor(maxBytesPerChunk)

  def withLevel(level: Int): Deflate = new Deflate(level, messageFilter)
}
object Deflate extends Deflate(Encoder.DefaultFilter)
