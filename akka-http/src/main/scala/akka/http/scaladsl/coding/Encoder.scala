/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.coding

import akka.NotUsed
import akka.annotation.InternalApi
import akka.http.scaladsl.model._
import akka.http.impl.util.StreamUtils
import akka.stream.{ FlowShape, Materializer }
import akka.stream.stage.GraphStage
import akka.util.ByteString
import headers._
import akka.stream.scaladsl.{ Flow, Sink, Source }

import scala.concurrent.Future

trait Encoder {
  def encoding: HttpEncoding

  def messageFilter: HttpMessage => Boolean

  def encodeMessage(message: HttpMessage): message.Self =
    if (messageFilter(message) && !message.headers.exists(Encoder.isContentEncodingHeader))
      message.transformEntityDataBytes(singleUseEncoderFlow()).withHeaders(`Content-Encoding`(encoding) +: message.headers)
    else message.self

  def encodeData[T](t: T)(implicit mapper: DataMapper[T]): T =
    mapper.transformDataBytes(t, Flow.fromGraph(singleUseEncoderFlow()))

  def encoderFlow: Flow[ByteString, ByteString, NotUsed] =
    Flow.setup { (_, _) => Flow.fromGraph(singleUseEncoderFlow()) }
      .mapMaterializedValue(_ => NotUsed)

  @InternalApi
  @deprecated("synchronous compression with `encode` is not supported in the future any more, use `encodeAsync` instead", since = "10.2.0")
  def encode(input: ByteString): ByteString = newCompressor.compressAndFinish(input)

  def encodeAsync(input: ByteString)(implicit mat: Materializer): Future[ByteString] =
    Source.single(input).via(singleUseEncoderFlow()).runWith(Sink.fold(ByteString.empty)(_ ++ _))

  @InternalApi
  @deprecated("newCompressor is internal API", since = "10.2.0")
  def newCompressor: Compressor

  @InternalApi
  @deprecated("newEncodeTransformer is internal API", since = "10.2.0")
  def newEncodeTransformer(): GraphStage[FlowShape[ByteString, ByteString]] = singleUseEncoderFlow()

  private def singleUseEncoderFlow(): GraphStage[FlowShape[ByteString, ByteString]] = {
    val compressor = newCompressor

    def encodeChunk(bytes: ByteString): ByteString = compressor.compressAndFlush(bytes)
    def finish(): ByteString = compressor.finish()

    StreamUtils.byteStringTransformer(encodeChunk, () => finish())
  }
}

object Encoder {
  val DefaultFilter: HttpMessage => Boolean = {
    case req: HttpRequest                    => isCompressible(req)
    case res @ HttpResponse(status, _, _, _) => isCompressible(res) && status.allowsEntity
  }
  private[coding] def isCompressible(msg: HttpMessage): Boolean =
    msg.entity.contentType.mediaType.isCompressible

  private[coding] val isContentEncodingHeader: HttpHeader => Boolean = _.isInstanceOf[`Content-Encoding`]
}

/** A stateful object representing ongoing compression. */
@InternalApi
@deprecated("Compressor is internal API and will be moved or removed in the future.", since = "10.2.0")
abstract class Compressor {
  /**
   * Compresses the given input and returns compressed data. The implementation
   * can and will choose to buffer output data to improve compression. Use
   * `flush` or `compressAndFlush` to make sure that all input data has been
   * compressed and pending output data has been returned.
   */
  def compress(input: ByteString): ByteString

  /**
   * Flushes any output data and returns the currently remaining compressed data.
   */
  def flush(): ByteString

  /**
   * Closes this compressed stream and return the remaining compressed data. After
   * calling this method, this Compressor cannot be used any further.
   */
  def finish(): ByteString

  /** Combines `compress` + `flush` */
  def compressAndFlush(input: ByteString): ByteString
  /** Combines `compress` + `finish` */
  def compressAndFinish(input: ByteString): ByteString
}
