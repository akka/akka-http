/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.coding

import akka.annotation.InternalApi
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.HttpEncodings

@InternalApi
@deprecated("Actual implementation of Gzip is internal, use Coders.Gzip instead", since = "10.2.0")
class Gzip private[http] (compressionLevel: Int, val messageFilter: HttpMessage => Boolean) extends Coder with StreamDecoder {
  def this(messageFilter: HttpMessage => Boolean) = {
    this(GzipCompressor.DefaultCompressionLevel, messageFilter)
  }

  val encoding = HttpEncodings.gzip
  def newCompressor = new GzipCompressor(compressionLevel)
  def newDecompressorStage(maxBytesPerChunk: Int) = () => new GzipDecompressor(maxBytesPerChunk)

  @deprecated("Use Coders.Gzip(compressionLevel = ...) instead", since = "10.2.0")
  def withLevel(level: Int): Gzip = new Gzip(level, messageFilter)
}

/**
 * An encoder and decoder for the HTTP 'gzip' encoding.
 */
@InternalApi
@deprecated("Actual implementation of Gzip is internal API, use Coders.Gzip instead", since = "10.2.0")
object Gzip extends Gzip(Encoder.DefaultFilter) {
  def apply(messageFilter: HttpMessage => Boolean) = new Gzip(messageFilter)
}
