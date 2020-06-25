/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.coding

import akka.annotation.InternalApi
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.HttpEncodings

@InternalApi
@deprecated("Actual implementation of Deflate is internal API, use Coders.Deflate instead", since = "10.2.0")
class Deflate private[http] (compressionLevel: Int, val messageFilter: HttpMessage => Boolean) extends Coder with StreamDecoder {
  def this(messageFilter: HttpMessage => Boolean) = {
    this(DeflateCompressor.DefaultCompressionLevel, messageFilter)
  }

  val encoding = HttpEncodings.deflate
  def newCompressor = new DeflateCompressor(compressionLevel)
  def newDecompressorStage(maxBytesPerChunk: Int) = () => new DeflateDecompressor(maxBytesPerChunk)

  @deprecated("Use Coders.Deflate(compressionLevel = ...) instead", since = "10.2.0")
  def withLevel(level: Int): Deflate = new Deflate(level, messageFilter)
}
@InternalApi
@deprecated("Actual implementation of Deflate is internal API, use Coders.Deflate instead", since = "10.2.0")
object Deflate extends Deflate(Encoder.DefaultFilter)
