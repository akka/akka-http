/*
 * Copyright (C) 2009-2025 Lightbend Inc. <https://akka.io>
 */

package akka.http.scaladsl.marshallers.sprayjson

import akka.http.javadsl.{ common, model => jm }
import akka.NotUsed
import akka.event.Logging
import akka.http.scaladsl.common.EntityStreamingSupport
import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.model.MediaTypes.`application/json`
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.ws.{ BinaryMessage, Message, TextMessage }
import akka.http.scaladsl.unmarshalling.Unmarshaller.UnsupportedContentTypeException
import akka.http.scaladsl.unmarshalling.{ FromByteStringUnmarshaller, FromEntityUnmarshaller, Unmarshaller }
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.{ Flow, Keep, Sink, Source }
import akka.util.ByteString
import spray.json._

import scala.concurrent.duration._
import scala.language.implicitConversions
import scala.util.Try

/**
 * A trait providing automatic to and from JSON marshalling/unmarshalling using an in-scope *spray-json* protocol.
 */
trait SprayJsonSupport {
  implicit def sprayJsonUnmarshallerConverter[T](reader: RootJsonReader[T]): FromEntityUnmarshaller[T] =
    sprayJsonUnmarshaller(reader)

  implicit def sprayJsonUnmarshaller[T](implicit reader: RootJsonReader[T]): FromEntityUnmarshaller[T] =
    sprayJsValueUnmarshaller.map(jsonReader[T].read)
  implicit def sprayJsValueUnmarshaller: FromEntityUnmarshaller[JsValue] =
    Unmarshaller.byteStringUnmarshaller
      .forContentTypes(`application/json`)
      .andThen(sprayJsValueByteStringUnmarshaller)

  implicit def sprayJsValueByteStringUnmarshaller[T]: FromByteStringUnmarshaller[JsValue] =
    Unmarshaller.withMaterializer[ByteString, JsValue](_ => _ => { bs =>
      // .compact so addressing into any address is very fast (also for large chunks)
      // TODO we could optimise ByteStrings to better handle linear access like this (or provide ByteStrings.linearAccessOptimised)
      // TODO IF it's worth it.
      val parserInput = new SprayJsonByteStringParserInput(bs.compact)
      FastFuture.successful(JsonParser(parserInput))
    })
  implicit def sprayJsonByteStringUnmarshaller[T](implicit reader: RootJsonReader[T]): FromByteStringUnmarshaller[T] =
    sprayJsValueByteStringUnmarshaller[T].map(jsonReader[T].read)

  // support for as[Source[T, NotUsed]]
  implicit def sprayJsonSourceReader[T](implicit reader: RootJsonReader[T], support: EntityStreamingSupport): FromEntityUnmarshaller[Source[T, NotUsed]] =
    Unmarshaller.withMaterializer { implicit ec => implicit mat => e =>
      if (support.supported.matches(e.contentType)) {
        val frames = e.dataBytes.via(support.framingDecoder)
        val unmarshal = sprayJsonByteStringUnmarshaller(reader)(_)
        val unmarshallingFlow =
          if (support.unordered) Flow[ByteString].mapAsyncUnordered(support.parallelism)(unmarshal)
          else Flow[ByteString].mapAsync(support.parallelism)(unmarshal)
        val elements = frames.viaMat(unmarshallingFlow)(Keep.right)
        FastFuture.successful(elements)
      } else FastFuture.failed(UnsupportedContentTypeException(Some(e.contentType), support.supported))
    }

  //#sprayJsonMarshallerConverter
  implicit def sprayJsonMarshallerConverter[T](writer: RootJsonWriter[T])(implicit printer: JsonPrinter = CompactPrinter): ToEntityMarshaller[T] =
    sprayJsonMarshaller[T](writer, printer)
  //#sprayJsonMarshallerConverter
  implicit def sprayJsonMarshaller[T](implicit writer: RootJsonWriter[T], printer: JsonPrinter = CompactPrinter): ToEntityMarshaller[T] =
    sprayJsValueMarshaller compose writer.write
  implicit def sprayJsValueMarshaller(implicit printer: JsonPrinter = CompactPrinter): ToEntityMarshaller[JsValue] =
    Marshaller.StringMarshaller.wrap(MediaTypes.`application/json`)(printer)

  /**
   * Marshals any value for which a spray-json `RootJsonWriter` is in scope into a strict WebSocket `TextMessage`,
   * e.g. `Marshal(myMsg).to[Message]`.
   */
  implicit def sprayJsonTextMessageMarshaller[T](implicit writer: RootJsonWriter[T], printer: JsonPrinter = CompactPrinter): Marshaller[T, TextMessage] =
    sprayJsValueTextMessageMarshaller compose writer.write

  /**
   * Marshals a `JsValue` into a strict WebSocket `TextMessage`.
   */
  implicit def sprayJsValueTextMessageMarshaller(implicit printer: JsonPrinter = CompactPrinter): Marshaller[JsValue, TextMessage] =
    Marshaller.opaque[JsValue, TextMessage](js => TextMessage.Strict(printer(js)))

  /**
   * Maximum time to wait for a streamed WebSocket `TextMessage` to be fully received when unmarshalling it
   * with the implicit message unmarshallers. Override to change the default of 5 seconds, or use
   * `sprayJsonMessageUnmarshallerWithTimeout` / `sprayJsValueMessageUnmarshallerWithTimeout`.
   */
  protected def sprayJsonMessageStrictTimeout: FiniteDuration = 5.seconds

  /**
   * Unmarshals a WebSocket `Message` into any value for which a spray-json `RootJsonReader` is in scope,
   * e.g. `Unmarshal(message).to[MyMsg]`. Both strict and streamed text messages are supported,
   * streamed messages are collected using `sprayJsonMessageStrictTimeout`. Binary messages are rejected.
   */
  implicit def sprayJsonMessageUnmarshaller[T](implicit reader: RootJsonReader[T]): Unmarshaller[Message, T] =
    sprayJsonMessageUnmarshallerWithTimeout[T](sprayJsonMessageStrictTimeout)

  /**
   * Unmarshals a WebSocket `Message` into a `JsValue`. Both strict and streamed text messages are supported,
   * streamed messages are collected using `sprayJsonMessageStrictTimeout`. Binary messages are rejected.
   */
  implicit def sprayJsValueMessageUnmarshaller: Unmarshaller[Message, JsValue] =
    sprayJsValueMessageUnmarshallerWithTimeout(sprayJsonMessageStrictTimeout)

  /**
   * Like `sprayJsonMessageUnmarshaller` but with an explicit timeout for collecting streamed text messages.
   */
  def sprayJsonMessageUnmarshallerWithTimeout[T](timeout: FiniteDuration)(implicit reader: RootJsonReader[T]): Unmarshaller[Message, T] =
    sprayJsValueMessageUnmarshallerWithTimeout(timeout).map(jsonReader[T].read)

  /**
   * Like `sprayJsValueMessageUnmarshaller` but with an explicit timeout for collecting streamed text messages.
   */
  def sprayJsValueMessageUnmarshallerWithTimeout(timeout: FiniteDuration): Unmarshaller[Message, JsValue] =
    Unmarshaller.withMaterializer[Message, JsValue](implicit ec => implicit mat => { message =>
      message match {
        case TextMessage.Strict(text) =>
          FastFuture(Try(JsonParser(text)))
        case streamed: TextMessage =>
          streamed.toStrict(timeout).flatMap(strict => FastFuture(Try(JsonParser(strict.text))))
        case binary: BinaryMessage =>
          // drain the data so the WebSocket stream does not stall
          binary.dataStream.runWith(Sink.ignore)
          FastFuture.failed(new IllegalArgumentException("Expected a WebSocket TextMessage containing JSON but got a BinaryMessage"))
      }
    })
}
object SprayJsonSupport extends SprayJsonSupport

/**
 * Entity streaming support, implemented using spray-json.
 *
 * See also <a href="https://github.com/spray/spray-json">github.com/spray/spray-json</a> for details about Spray JSON itself
 */
object SprayJsonEntityStreamingSupport {

  /**
   * Default `application/json` entity streaming support.
   *
   * Provides framing (based on scanning the incoming dataBytes for valid JSON objects, so for example uploads using arrays or
   * new-line separated JSON objects are all parsed correctly) and rendering of Sources as JSON Arrays.
   * A different very popular style of returning streaming JSON is to separate JSON objects on a line-by-line basis,
   * you can configure the support trait to do so by calling `withFramingRendererFlow`.
   *
   * Limits the maximum JSON object length to 8KB, if you want to increase this limit provide a value explicitly.
   *
   * See also <a href="https://en.wikipedia.org/wiki/JSON_Streaming">https://en.wikipedia.org/wiki/JSON_Streaming</a>
   */
  def json(): JsonEntityStreamingSupport = json(8 * 1024)

  /**
   * Default `application/json` entity streaming support.
   *
   * Provides framing (based on scanning the incoming dataBytes for valid JSON objects, so for example uploads using arrays or
   * new-line separated JSON objects are all parsed correctly) and rendering of Sources as JSON Arrays.
   * A different very popular style of returning streaming JSON is to separate JSON objects on a line-by-line basis,
   * you can configure the support trait to do so by calling `withFramingRendererFlow`.
   *
   * See also <a href="https://en.wikipedia.org/wiki/JSON_Streaming">https://en.wikipedia.org/wiki/JSON_Streaming</a>
   */
  def json(maxObjectLength: Int): JsonEntityStreamingSupport = new JsonEntityStreamingSupport(maxObjectLength)

}

final class JsonEntityStreamingSupport private[akka] (
  maxObjectSize:       Int,
  val supported:       ContentTypeRange,
  val contentType:     ContentType,
  val framingRenderer: Flow[ByteString, ByteString, NotUsed],
  val parallelism:     Int,
  val unordered:       Boolean
) extends common.JsonEntityStreamingSupport {
  import akka.http.impl.util.JavaMapping.Implicits._

  def this(maxObjectSize: Int) =
    this(
      maxObjectSize,
      ContentTypeRange(ContentTypes.`application/json`),
      ContentTypes.`application/json`,
      Flow[ByteString].intersperse(ByteString("["), ByteString(","), ByteString("]")),
      1, false)

  override val framingDecoder: Flow[ByteString, ByteString, NotUsed] =
    akka.stream.scaladsl.JsonFraming.objectScanner(maxObjectSize)

  override def withFramingRendererFlow(framingRendererFlow: akka.stream.javadsl.Flow[ByteString, ByteString, NotUsed]): JsonEntityStreamingSupport =
    withFramingRenderer(framingRendererFlow.asScala)
  def withFramingRenderer(framingRendererFlow: Flow[ByteString, ByteString, NotUsed]): JsonEntityStreamingSupport =
    new JsonEntityStreamingSupport(maxObjectSize, supported, contentType, framingRendererFlow, parallelism, unordered)

  override def withContentType(ct: jm.ContentType): JsonEntityStreamingSupport =
    new JsonEntityStreamingSupport(maxObjectSize, supported, ct.asScala, framingRenderer, parallelism, unordered)
  override def withSupported(range: jm.ContentTypeRange): JsonEntityStreamingSupport =
    new JsonEntityStreamingSupport(maxObjectSize, range.asScala, contentType, framingRenderer, parallelism, unordered)
  override def withParallelMarshalling(parallelism: Int, unordered: Boolean): JsonEntityStreamingSupport =
    new JsonEntityStreamingSupport(maxObjectSize, supported, contentType, framingRenderer, parallelism, unordered)

  override def toString = s"""${Logging.simpleName(getClass)}($maxObjectSize, $supported, $contentType)"""

}
