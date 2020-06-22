/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.coding

import akka.NotUsed
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.HttpEncodings
import akka.stream.scaladsl.{ Compression, Flow }
import akka.util.ByteString

class Gzip private (compressionLevel: Int, val messageFilter: HttpMessage => Boolean) extends Coder with StreamDecoder { outer =>
  def this(messageFilter: HttpMessage => Boolean) = {
    this(GzipCompressor.DefaultCompressionLevel, messageFilter)
  }

  val encoding = HttpEncodings.gzip

  override def encoderFlow: Flow[ByteString, ByteString, NotUsed] = Compression.gzip(compressionLevel)
  override def decoderFlow: Flow[ByteString, ByteString, NotUsed] = Compression.gunzip()

  override def withMaxBytesPerChunk(newMaxBytesPerChunk: Int): Decoder = new Gzip(compressionLevel, messageFilter) {
    override def withMaxBytesPerChunk(newMaxBytesPerChunk: Int): Decoder = outer.withMaxBytesPerChunk(newMaxBytesPerChunk)
    override def decoderFlow: Flow[ByteString, ByteString, NotUsed] = Compression.gunzip(newMaxBytesPerChunk)
  }

  def newCompressor = new GzipCompressor(compressionLevel)
  // will usually not be used any more, since encoderFlow / decoderFlow have been overridden
  def newDecompressorStage(maxBytesPerChunk: Int) = () => new GzipDecompressor(maxBytesPerChunk)

  def withLevel(level: Int): Gzip = new Gzip(level, messageFilter)
}

/**
 * An encoder and decoder for the HTTP 'gzip' encoding.
 */
object Gzip extends Gzip(Encoder.DefaultFilter) {
  def apply(messageFilter: HttpMessage => Boolean) = new Gzip(messageFilter)
}
