/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.coding

import akka.annotation.InternalApi
import akka.http.scaladsl.model._
import akka.http.impl.util.StreamUtils
import akka.stream.FlowShape
import akka.stream.stage.GraphStage
import akka.util.ByteString
import headers.HttpEncodings

/**
 * An encoder and decoder for the HTTP 'identity' encoding.
 */
@InternalApi
@deprecated("Actual implementation of NoCoding is internal API, use Coders.NoCoding instead", since = "10.2.0")
object NoCoding extends Coder with StreamDecoder {
  val encoding = HttpEncodings.identity

  override def encodeData[T](t: T)(implicit mapper: DataMapper[T]): T = t
  override def decodeData[T](t: T)(implicit mapper: DataMapper[T]): T = t

  val messageFilter: HttpMessage => Boolean = _ => false

  def newCompressor = NoCodingCompressor

  def newDecompressorStage(maxBytesPerChunk: Int): () => GraphStage[FlowShape[ByteString, ByteString]] =
    () => StreamUtils.limitByteChunksStage(maxBytesPerChunk)
}

/** Internal API */
@InternalApi
@deprecated("NoCodingCompressor is internal API and will be moved or removed in the future", since = "10.2.0")
object NoCodingCompressor extends Compressor {
  def compress(input: ByteString): ByteString = input
  def flush() = ByteString.empty
  def finish() = ByteString.empty

  def compressAndFlush(input: ByteString): ByteString = input
  def compressAndFinish(input: ByteString): ByteString = input
}
