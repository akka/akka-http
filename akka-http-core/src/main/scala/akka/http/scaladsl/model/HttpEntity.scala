/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.model

import language.implicitConversions

import java.io.File
import java.nio.file.{ Files, Path }
import java.lang.{ Iterable => JIterable }
import java.util.concurrent.CompletionStage
import java.util.OptionalLong

import scala.annotation.nowarn
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.collection.immutable
import scala.jdk.OptionConverters._
import scala.jdk.FutureConverters._
import scala.util.control.NonFatal

import akka.actor.ClassicActorSystemProvider
import akka.annotation.{ DoNotInherit, InternalApi }
import akka.stream.scaladsl._
import akka.stream.stage._
import akka.stream._
import akka.{ Done, NotUsed, stream }
import akka.http.scaladsl.util.FastFuture
import akka.http.javadsl.{ model => jm }
import akka.http.impl.util.{ JavaMapping, StreamUtils }
import akka.http.impl.util.JavaMapping.Implicits._
import akka.util.ByteString

/**
 * Models the entity (aka "body" or "content") of an HTTP message.
 */
sealed trait HttpEntity extends jm.HttpEntity {
  import language.implicitConversions
  private implicit def completionStageCovariant[T, U >: T](in: CompletionStage[T]): CompletionStage[U] = in.asInstanceOf[CompletionStage[U]]

  /**
   * Determines whether this entity is known to be empty.
   */
  override def isKnownEmpty: Boolean

  /**
   * The `ContentType` associated with this entity.
   */
  def contentType: ContentType

  /**
   * Some(content length) if a length is defined for this entity, None otherwise.
   * A length is only defined for Strict and Default entity types.
   *
   * In many cases it's dangerous to rely on the (non-)existence of a content-length.
   * HTTP intermediaries like (transparent) proxies are allowed to change the transfer-encoding
   * which can result in the entity being delivered as another type as expected.
   */
  def contentLengthOption: Option[Long]

  /**
   * A stream of the data of this entity.
   */
  def dataBytes: Source[ByteString, Any]

  /**
   * Collects all possible parts and returns a potentially future Strict entity for easier processing.
   * The Future is failed with an TimeoutException if the stream isn't completed after the given timeout,
   * or with a EntityStreamException when the end of the entity is not reached within the maximum number of bytes
   * as configured in `akka.http.parsing.max-to-strict-bytes`. Not that this method does not support different
   * defaults for client- and server use: if you want that, use the `toStrict` method and pass in an explicit
   * maximum number of bytes.
   */
  def toStrict(timeout: FiniteDuration)(implicit fm: Materializer): Future[HttpEntity.Strict] = {
    import akka.http.impl.util._
    val config = fm.system.settings.config
    toStrict(timeout, config.getPossiblyInfiniteBytes("akka.http.parsing.max-to-strict-bytes"))
  }

  /**
   * Collects all possible parts and returns a potentially future Strict entity for easier processing.
   * The Future is failed with an TimeoutException if the stream isn't completed after the given timeout,
   * or with a EntityStreamException when the end of the entity is not reached within the maximum number of bytes.
   */
  def toStrict(timeout: FiniteDuration, maxBytes: Long)(implicit fm: Materializer): Future[HttpEntity.Strict] = contentLengthOption match {
    case Some(contentLength) if contentLength > maxBytes =>
      FastFuture.failed(new EntityStreamException(new ErrorInfo("Request too large", s"Request of size $contentLength was longer than the maximum of $maxBytes")))
    case _ =>
      dataBytes
        .via(new akka.http.impl.util.ToStrict(timeout, Some(maxBytes), contentType))
        .runWith(Sink.head)
  }

  /**
   * Discards the entities data bytes by running the `dataBytes` Source contained in this `entity`.
   *
   * Note: It is crucial that entities are either discarded, or consumed by running the underlying [[akka.stream.scaladsl.Source]]
   * as otherwise the lack of consuming of the data will trigger back-pressure to the underlying TCP connection
   * (as designed), however possibly leading to an idle-timeout that will close the connection, instead of
   * just having ignored the data.
   *
   * Warning: It is not allowed to discard and/or consume the `entity.dataBytes` more than once
   * as the stream is directly attached to the "live" incoming data source from the underlying TCP connection.
   * Allowing it to be consumable twice would require buffering the incoming data, thus defeating the purpose
   * of its streaming nature. If the dataBytes source is materialized a second time, it will fail with an
   * "stream can cannot be materialized more than once" exception.
   *
   * When called on `Strict` entities or sources whose values can be buffered in memory,
   * the above warnings can be ignored. Repeated materialization is not necessary in this case, avoiding
   * the mentioned exceptions due to the data being held in memory.
   *
   * In future versions, more automatic ways to warn or resolve these situations may be introduced, see issue #18716.
   */
  override def discardBytes(mat: Materializer): HttpMessage.DiscardedEntity =
    if (isStrict) HttpMessage.AlreadyDiscardedEntity
    else new HttpMessage.DiscardedEntity(dataBytes.runWith(Sink.ignore)(mat))

  /** Java API */
  def discardBytes(system: ClassicActorSystemProvider): HttpMessage.DiscardedEntity =
    discardBytes(SystemMaterializer(system).materializer)

  /**
   * Returns a copy of the given entity with the ByteString chunks of this entity transformed by the given transformer.
   * For a `Chunked` entity, the chunks will be transformed one by one keeping the chunk metadata (but may introduce an
   * extra chunk before the `LastChunk` if `transformer.onTermination` returns additional data).
   *
   * This method may only throw an exception if the `transformer` function throws an exception while creating the transformer.
   * Any other errors are reported through the new entity data stream.
   */
  def transformDataBytes(transformer: Flow[ByteString, ByteString, Any]): HttpEntity

  /**
   * Transforms this' entities data bytes with a transformer that will produce exactly the number of bytes given as
   * `newContentLength`.
   */
  def transformDataBytes(newContentLength: Long, transformer: Flow[ByteString, ByteString, Any]): UniversalEntity =
    HttpEntity.Default(contentType, newContentLength, dataBytes via transformer)

  /**
   * Creates a copy of this HttpEntity with the `contentType` overridden with the given one.
   */
  def withContentType(contentType: ContentType): HttpEntity

  /**
   * Apply the given size limit to this entity by returning a new entity instance which automatically verifies that the
   * data stream encapsulated by this instance produces at most `maxBytes` data bytes. In case this verification fails
   * the respective stream will be terminated with an `EntityStreamException` either directly at materialization
   * time (if the Content-Length is known) or whenever more data bytes than allowed have been read.
   *
   * When called on `Strict` entities the method will return the entity itself if the length is within the bound,
   * otherwise a `Default` entity with a single element data stream. This allows for potential refinement of the
   * entity size limit at a later point (before materialization of the data stream).
   *
   * By default all message entities produced by the HTTP layer automatically carry the limit that is defined in the
   * application's `max-content-length` config setting. If the entity is transformed in a way that changes the
   * Content-Length and then another limit is applied then this new limit will be evaluated against the new
   * Content-Length. If the entity is transformed in a way that changes the Content-Length and no new limit is applied
   * then the previous limit will be applied against the previous Content-Length.
   */
  override def withSizeLimit(maxBytes: Long): HttpEntity

  /**
   * Lift the size limit from this entity by returning a new entity instance which skips the size verification.
   *
   * By default all message entities produced by the HTTP layer automatically carry the limit that is defined in the
   * application's `max-content-length` config setting. It is recommended to always keep an upper limit on accepted
   * entities to avoid potential attackers flooding you with too large requests/responses, so use this method with caution.
   *
   * See [[withSizeLimit]] for more details.
   */
  override def withoutSizeLimit: HttpEntity

  /** Java API */
  override def getContentType: jm.ContentType = contentType

  /** Java API */
  override def getDataBytes: stream.javadsl.Source[ByteString, AnyRef] =
    stream.javadsl.Source.fromGraph(dataBytes.asInstanceOf[Source[ByteString, AnyRef]])

  /** Java API */
  override def getContentLengthOption: OptionalLong = contentLengthOption.toJavaPrimitive

  // default implementations, should be overridden
  override def isCloseDelimited: Boolean = false
  override def isIndefiniteLength: Boolean = false
  override def isStrict: Boolean = false
  override def isDefault: Boolean = false
  override def isChunked: Boolean = false

  /** Java API */
  override def toStrict(timeoutMillis: Long, materializer: Materializer): CompletionStage[jm.HttpEntity.Strict] =
    toStrict(timeoutMillis.millis)(materializer).asJava

  /** Java API */
  override def toStrict(timeoutMillis: Long, maxBytes: Long, materializer: Materializer): CompletionStage[jm.HttpEntity.Strict] =
    toStrict(timeoutMillis.millis, maxBytes)(materializer).asJava

  /** Java API */
  override def toStrict(timeoutMillis: Long, system: ClassicActorSystemProvider): CompletionStage[jm.HttpEntity.Strict] =
    toStrict(timeoutMillis.millis)(SystemMaterializer(system).materializer).asJava

  /** Java API */
  override def toStrict(timeoutMillis: Long, maxBytes: Long, system: ClassicActorSystemProvider): CompletionStage[jm.HttpEntity.Strict] =
    toStrict(timeoutMillis.millis, maxBytes)(SystemMaterializer(system).materializer).asJava

  /** Java API */
  override def withContentType(contentType: jm.ContentType): HttpEntity = {
    import JavaMapping.Implicits._
    withContentType(contentType.asScala)
  }
}

/* An entity that can be used for body parts */
sealed trait BodyPartEntity extends HttpEntity with jm.BodyPartEntity {
  override def withContentType(contentType: ContentType): BodyPartEntity

  override def withSizeLimit(maxBytes: Long): BodyPartEntity
  override def withoutSizeLimit: BodyPartEntity
}

/**
 * An [[HttpEntity]] that can be used for requests.
 * Note that all entities that can be used for requests can also be used for responses.
 * (But not the other way around, since [[HttpEntity.CloseDelimited]] can only be used for responses!)
 */
sealed trait RequestEntity extends HttpEntity with jm.RequestEntity with ResponseEntity {
  def withContentType(contentType: ContentType): RequestEntity

  /**
   * See [[HttpEntity#withSizeLimit]].
   */
  def withSizeLimit(maxBytes: Long): RequestEntity

  /**
   * See [[HttpEntity#withoutSizeLimit]].
   */
  def withoutSizeLimit: RequestEntity

  def transformDataBytes(transformer: Flow[ByteString, ByteString, Any]): RequestEntity
}

/**
 * An [[HttpEntity]] that can be used for responses.
 * Note that all entities that can be used for requests can also be used for responses.
 * (But not the other way around, since [[HttpEntity.CloseDelimited]] can only be used for responses!)
 */
sealed trait ResponseEntity extends HttpEntity with jm.ResponseEntity {
  def withContentType(contentType: ContentType): ResponseEntity

  /**
   * See [[HttpEntity#withSizeLimit]].
   */
  def withSizeLimit(maxBytes: Long): ResponseEntity

  /**
   * See [[HttpEntity#withoutSizeLimit]]
   */
  def withoutSizeLimit: ResponseEntity

  def transformDataBytes(transformer: Flow[ByteString, ByteString, Any]): ResponseEntity
}

object ResponseEntity {
  implicit def fromJava(entity: akka.http.javadsl.model.ResponseEntity)(implicit m: JavaMapping[akka.http.javadsl.model.ResponseEntity, ResponseEntity]): ResponseEntity =
    JavaMapping.toScala(entity)
}

/* An entity that can be used for requests, responses, and body parts */
sealed trait UniversalEntity extends jm.UniversalEntity with MessageEntity with BodyPartEntity {
  def withContentType(contentType: ContentType): UniversalEntity

  /**
   * See [[HttpEntity#withSizeLimit]].
   */
  def withSizeLimit(maxBytes: Long): UniversalEntity

  /**
   * See [[HttpEntity#withoutSizeLimit]]
   */
  def withoutSizeLimit: UniversalEntity

  def contentLength: Long
  def contentLengthOption: Option[Long] = Some(contentLength)
}

object HttpEntity {
  implicit def apply(string: String): HttpEntity.Strict = apply(ContentTypes.`text/plain(UTF-8)`, string)
  implicit def apply(bytes: Array[Byte]): HttpEntity.Strict = apply(ContentTypes.`application/octet-stream`, bytes)
  implicit def apply(data: ByteString): HttpEntity.Strict = apply(ContentTypes.`application/octet-stream`, data)
  def apply(contentType: ContentType.NonBinary, string: String): HttpEntity.Strict =
    if (string.isEmpty) empty(contentType) else apply(contentType, ByteString.fromArrayUnsafe(string.getBytes(contentType.charset.nioCharset)))
  def apply(contentType: ContentType.WithFixedCharset, string: String): HttpEntity.Strict =
    if (string.isEmpty) empty(contentType) else apply(contentType, ByteString.fromArrayUnsafe(string.getBytes(contentType.charset.nioCharset)))
  def apply(contentType: ContentType, bytes: Array[Byte]): HttpEntity.Strict =
    if (bytes.length == 0) empty(contentType) else apply(contentType, ByteString(bytes))
  def apply(contentType: ContentType, data: ByteString): HttpEntity.Strict =
    if (data.isEmpty) empty(contentType) else HttpEntity.Strict(contentType, data)

  def apply(contentType: ContentType, contentLength: Long, data: Source[ByteString, Any]): UniversalEntity =
    if (contentLength == 0) empty(contentType) else HttpEntity.Default(contentType, contentLength, data)
  def apply(contentType: ContentType, data: Source[ByteString, Any]): HttpEntity.Chunked =
    HttpEntity.Chunked.fromData(contentType, data)

  /**
   * Returns either the empty entity, if the given file is empty, or a [[HttpEntity.Default]] entity
   * consisting of a stream of [[akka.util.ByteString]] instances each containing `chunkSize` bytes
   * (except for the final ByteString, which simply contains the remaining bytes).
   *
   * If the given `chunkSize` is -1 the default chunk size is used.
   */
  def fromFile(contentType: ContentType, file: File, chunkSize: Int = -1): UniversalEntity =
    fromPath(contentType, file.toPath, chunkSize)

  /**
   * Returns either the empty entity, if the given file is empty, or a [[HttpEntity.Default]] entity
   * consisting of a stream of [[akka.util.ByteString]] instances each containing `chunkSize` bytes
   * (except for the final ByteString, which simply contains the remaining bytes).
   *
   * If the given `chunkSize` is -1 the default chunk size is used.
   */
  def fromPath(contentType: ContentType, file: Path, chunkSize: Int = -1): UniversalEntity = {
    val fileLength = Files.size(file)
    if (fileLength > 0)
      HttpEntity.Default(contentType, fileLength,
        if (chunkSize > 0) FileIO.fromPath(file, chunkSize) else FileIO.fromPath(file))
    else empty(contentType)
  }

  val Empty: HttpEntity.Strict = HttpEntity.Strict(ContentTypes.NoContentType, data = ByteString.empty)

  def empty(contentType: ContentType): HttpEntity.Strict =
    if (contentType == Empty.contentType) Empty
    else HttpEntity.Strict(contentType, data = ByteString.empty)

  // TODO: re-establish serializability
  // TODO: equal/hashcode ?

  /**
   * The model for the entity of a "regular" unchunked HTTP message with known, fixed data.
   */
  final case class Strict(contentType: ContentType, data: ByteString)
    extends jm.HttpEntity.Strict with UniversalEntity {

    override def contentLength: Long = data.length
    override def isKnownEmpty: Boolean = data.isEmpty
    override def isStrict: Boolean = true

    override def dataBytes: Source[ByteString, NotUsed] = Source.single(data)

    override def toStrict(timeout: FiniteDuration)(implicit fm: Materializer) =
      FastFuture.successful(this)

    override def transformDataBytes(transformer: Flow[ByteString, ByteString, Any]): MessageEntity =
      HttpEntity.Chunked.fromData(contentType, Source.single(data).via(transformer))

    override def withContentType(contentType: ContentType): HttpEntity.Strict =
      if (contentType == this.contentType) this else copy(contentType = contentType)

    override def withSizeLimit(maxBytes: Long): UniversalEntity =
      if (data.length <= maxBytes || isKnownEmpty) this
      else HttpEntity.Default(contentType, data.length, Source.single(data)) withSizeLimit maxBytes

    override def withoutSizeLimit: UniversalEntity =
      withSizeLimit(SizeLimit.Disabled)

    override def productPrefix = "HttpEntity.Strict"

    override def toString = {
      val dataSizeStr = s"${data.length} bytes total"

      s"$productPrefix($contentType,$dataSizeStr)"
    }

    /** Java API */
    override def getData = data
  }

  /**
   * The model for the entity of a "regular" unchunked HTTP message with a known non-zero length.
   */
  final case class Default(
    contentType:   ContentType,
    contentLength: Long,
    data:          Source[ByteString, Any])
    extends jm.HttpEntity.Default with UniversalEntity {
    require(contentLength > 0, "contentLength must be positive (use `HttpEntity.empty(contentType)` for empty entities)")
    def isKnownEmpty = false
    override def isDefault: Boolean = true

    def dataBytes: Source[ByteString, Any] = data

    override def transformDataBytes(transformer: Flow[ByteString, ByteString, Any]): HttpEntity.Chunked =
      HttpEntity.Chunked.fromData(contentType, data via transformer)

    def withContentType(contentType: ContentType): HttpEntity.Default =
      if (contentType == this.contentType) this else copy(contentType = contentType)

    override def withSizeLimit(maxBytes: Long): HttpEntity.Default = {
      if (data ne Source.empty) copy(data = Limitable.applyForByteStrings(data, SizeLimit(maxBytes, Some(contentLength))))
      else this
    }

    override def withoutSizeLimit: HttpEntity.Default =
      withSizeLimit(SizeLimit.Disabled)

    override def productPrefix = "HttpEntity.Default"

    override def toString: String = {
      s"$productPrefix($contentType,$contentLength bytes total)"
    }

    /** Java API */
    override def getContentLength = contentLength
  }

  /**
   * Supertype of CloseDelimited and IndefiniteLength.
   *
   * INTERNAL API
   */
  @DoNotInherit
  private[http] sealed trait WithoutKnownLength extends HttpEntity {
    type Self <: HttpEntity.WithoutKnownLength
    def contentType: ContentType
    def data: Source[ByteString, Any]
    override def contentLengthOption: Option[Long] = None
    override def isKnownEmpty = data eq Source.empty
    override def dataBytes: Source[ByteString, Any] = data

    override def withSizeLimit(maxBytes: Long): Self =
      withData(Limitable.applyForByteStrings(data, SizeLimit(maxBytes)))

    override def withoutSizeLimit: Self =
      withSizeLimit(SizeLimit.Disabled)

    override def transformDataBytes(transformer: Flow[ByteString, ByteString, Any]): Self =
      withData(data via transformer)

    def withData(data: Source[ByteString, Any]): Self
  }

  /**
   * The model for the entity of an HTTP response that is terminated by the server closing the connection.
   * The content-length of such responses is unknown at the time the response headers have been received.
   * Note that this type of HttpEntity can only be used for HttpResponses.
   */
  final case class CloseDelimited(contentType: ContentType, data: Source[ByteString, Any])
    extends jm.HttpEntity.CloseDelimited with ResponseEntity with HttpEntity.WithoutKnownLength {
    type Self = HttpEntity.CloseDelimited

    override def isCloseDelimited: Boolean = true
    override def withContentType(contentType: ContentType): HttpEntity.CloseDelimited =
      if (contentType == this.contentType) this else copy(contentType = contentType)

    override def withData(data: Source[ByteString, Any]): HttpEntity.CloseDelimited = copy(data = data)

    override def productPrefix = "HttpEntity.CloseDelimited"

    override def toString: String = {
      s"$productPrefix($contentType)"
    }
  }

  /**
   * The model for the entity of a BodyPart with an indefinite length.
   * Note that this type of HttpEntity can only be used for BodyParts.
   */
  final case class IndefiniteLength(contentType: ContentType, data: Source[ByteString, Any])
    extends jm.HttpEntity.IndefiniteLength with BodyPartEntity with HttpEntity.WithoutKnownLength {
    type Self = HttpEntity.IndefiniteLength

    override def isIndefiniteLength: Boolean = true
    override def withContentType(contentType: ContentType): HttpEntity.IndefiniteLength =
      if (contentType == this.contentType) this else copy(contentType = contentType)

    override def withData(data: Source[ByteString, Any]): HttpEntity.IndefiniteLength = copy(data = data)

    override def productPrefix = "HttpEntity.IndefiniteLength"

    override def toString: String = {
      s"$productPrefix($contentType)"
    }
  }

  /**
   * The model for the entity of a chunked HTTP message (with `Transfer-Encoding: chunked`).
   */
  final case class Chunked(contentType: ContentType, chunks: Source[ChunkStreamPart, Any])
    extends jm.HttpEntity.Chunked with MessageEntity {

    override def isKnownEmpty = chunks eq Source.empty
    override def contentLengthOption: Option[Long] = None

    override def isChunked: Boolean = true

    override def dataBytes: Source[ByteString, Any] = chunks.map(_.data).filter(_.nonEmpty)

    override def withSizeLimit(maxBytes: Long): HttpEntity.Chunked =
      copy(chunks = Limitable.applyForChunks(chunks, SizeLimit(maxBytes)))

    override def withoutSizeLimit: HttpEntity.Chunked =
      withSizeLimit(SizeLimit.Disabled)

    override def transformDataBytes(transformer: Flow[ByteString, ByteString, Any]): HttpEntity.Chunked = {
      // This construction allows to keep trailing headers. For that the stream is split into two
      // tracks. One for the regular chunks and one for the LastChunk. Only the regular chunks are
      // run through the user-supplied transformer, the LastChunk is just passed on. The tracks are
      // then concatenated to produce the final stream.
      val transformChunks = GraphDSL.create() { implicit builder: GraphDSL.Builder[NotUsed] =>
        import akka.stream.scaladsl.GraphDSL.Implicits._

        val partition = builder.add(Partition[HttpEntity.ChunkStreamPart](2, {
          case c: Chunk     => 0
          case c: LastChunk => 1
        }))
        val concat = builder.add(Concat[HttpEntity.ChunkStreamPart](2))

        val chunkTransformer: Flow[HttpEntity.ChunkStreamPart, HttpEntity.ChunkStreamPart, Any] =
          Flow[HttpEntity.ChunkStreamPart]
            .map(_.data)
            .via(transformer)
            .map(b => Chunk(b))

        val trailerBypass: Flow[HttpEntity.ChunkStreamPart, HttpEntity.ChunkStreamPart, Any] =
          Flow[HttpEntity.ChunkStreamPart]
            // make sure to filter out any errors here, otherwise they don't go through the user transformer
            .recover { case NonFatal(ex) => Chunk(ByteString(0), "") }
            // only needed to filter the out the result from recover in the line above
            .collect { case lc @ LastChunk(_, s) if s.nonEmpty => lc }

        partition ~> chunkTransformer ~> concat
        partition ~> trailerBypass ~> concat
        FlowShape(partition.in, concat.out)
      }

      HttpEntity.Chunked(contentType, chunks.via(transformChunks))
    }

    def withContentType(contentType: ContentType): HttpEntity.Chunked =
      if (contentType == this.contentType) this else copy(contentType = contentType)

    override def productPrefix = "HttpEntity.Chunked"

    override def toString: String = {
      s"$productPrefix($contentType)"
    }

    /** Java API */
    def getChunks: stream.javadsl.Source[jm.HttpEntity.ChunkStreamPart, AnyRef] =
      stream.javadsl.Source.fromGraph(chunks.asInstanceOf[Source[jm.HttpEntity.ChunkStreamPart, AnyRef]])
  }
  object Chunked {
    /**
     * Returns a `Chunked` entity where one Chunk is produced for every non-empty ByteString produced by the given
     * `Source`.
     */
    def fromData(contentType: ContentType, chunks: Source[ByteString, Any]): HttpEntity.Chunked =
      HttpEntity.Chunked(contentType, chunks.collect[ChunkStreamPart] {
        case b: ByteString if b.nonEmpty => Chunk(b)
      })
  }

  /**
   * An element of the HttpEntity data stream.
   * Can be either a `Chunk` or a `LastChunk`.
   */
  sealed abstract class ChunkStreamPart extends jm.HttpEntity.ChunkStreamPart {
    def data: ByteString
    def extension: String
    def isLastChunk: Boolean
  }
  object ChunkStreamPart {
    implicit def apply(string: String): ChunkStreamPart = Chunk(string)
    implicit def apply(bytes: Array[Byte]): ChunkStreamPart = Chunk(bytes)
    implicit def apply(bytes: ByteString): ChunkStreamPart = Chunk(bytes)
  }

  /**
   * An intermediate entity chunk guaranteed to carry non-empty data.
   */
  final case class Chunk(data: ByteString, extension: String = "") extends HttpEntity.ChunkStreamPart {
    require(data.nonEmpty, "An HttpEntity.Chunk must have non-empty data")
    def isLastChunk = false

    /** Java API */
    def getTrailerHeaders: JIterable[jm.HttpHeader] = java.util.Collections.emptyList[jm.HttpHeader]
  }
  object Chunk {
    def apply(string: String): Chunk = apply(ByteString(string))
    def apply(bytes: Array[Byte]): Chunk = apply(ByteString(bytes))
  }

  /**
   * The final chunk of a chunk stream.
   * If you don't need extensions or trailer headers you can save an allocation
   * by directly using the `LastChunk` companion object.
   */
  case class LastChunk(extension: String = "", trailer: immutable.Seq[HttpHeader] = Nil) extends HttpEntity.ChunkStreamPart {
    def data = ByteString.empty
    def isLastChunk = true

    /** Java API */
    def getTrailerHeaders: JIterable[jm.HttpHeader] = trailer.asJava
  }
  object LastChunk extends LastChunk("", Nil)

  /**
   * Deprecated: no-op, not explicitly needed any more.
   */
  @deprecated("Not needed explicitly any more. ", "10.1.5")
  def limitableByteSource[Mat](source: Source[ByteString, Mat]): Source[ByteString, Mat] =
    source

  /**
   * Deprecated: no-op, not explicitly needed any more.
   */
  @deprecated("Not needed explicitly any more. ", "10.1.5")
  def limitableChunkSource[Mat](source: Source[ChunkStreamPart, Mat]): Source[ChunkStreamPart, Mat] =
    source

  private final case class SizeLimit(maxBytes: Long, contentLength: Option[Long] = None) extends Attributes.Attribute {
    def isDisabled = maxBytes < 0
  }
  private object SizeLimit {
    val Disabled = -1 // any negative value will do
  }

  private final class Limitable[T](sizeOf: T => Int) extends GraphStage[FlowShape[T, T]] {
    val in = Inlet[T]("Limitable.in")
    val out = Outlet[T]("Limitable.out")
    override val shape = FlowShape.of(in, out)
    override protected val initialAttributes: Attributes = Limitable.limitableDefaults

    override def createLogic(_attributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) with InHandler with OutHandler {
      private var maxBytes = -1L
      private var bytesLeft = Long.MaxValue

      @nowarn("msg=deprecated") // we need getFirst semantics
      override def preStart(): Unit = {
        _attributes.getFirst[SizeLimit] match {
          case Some(limit: SizeLimit) if limit.isDisabled =>
          // "no limit"
          case Some(SizeLimit(bytes, cl @ Some(contentLength))) =>
            if (contentLength > bytes) failStage(EntityStreamSizeException(bytes, cl))
          // else we still count but never throw an error
          case Some(SizeLimit(bytes, None)) =>
            maxBytes = bytes
            bytesLeft = bytes
          case None =>
        }
      }

      override def onPush(): Unit = {
        val elem = grab(in)
        bytesLeft -= sizeOf(elem)
        if (bytesLeft >= 0) push(out, elem)
        else failStage(EntityStreamSizeException(maxBytes))
      }

      override def onPull(): Unit = {
        pull(in)
      }

      setHandlers(in, out, this)
    }
  }
  private object Limitable {
    def applyForByteStrings[Mat](source: Source[ByteString, Mat], limit: SizeLimit): Source[ByteString, Mat] =
      applyLimit(source, limit)(_.size)

    def applyForChunks[Mat](source: Source[ChunkStreamPart, Mat], limit: SizeLimit): Source[ChunkStreamPart, Mat] =
      applyLimit(source, limit)(_.data.size)

    def applyLimit[T, Mat](source: Source[T, Mat], limit: SizeLimit)(sizeOf: T => Int): Source[T, Mat] =
      if (limit.isDisabled) source withAttributes Attributes(limit) // no need to add stage, it's either there or not needed
      else source.via(new Limitable(sizeOf)) withAttributes Attributes(limit)

    private val limitableDefaults = Attributes.name("limitable")
  }

  /**
   * INTERNAL API
   */
  @InternalApi
  private[http] def captureTermination[T <: HttpEntity](entity: T): (T, Future[Unit]) =
    if (entity.isStrict) (entity, StreamUtils.CaptureTerminationOp.strictM) // fast path for the common case
    else StreamUtils.transformEntityStream(entity, StreamUtils.CaptureTerminationOp)

  /**
   * Represents the currently being-drained HTTP Entity which triggers completion of the contained
   * Future once the entity has been drained for the given HttpMessage completely.
   */
  final class DiscardedEntity(f: Future[Done]) extends akka.http.javadsl.model.HttpMessage.DiscardedEntity {
    /**
     * This future completes successfully once the underlying entity stream has been
     * successfully drained (and fails otherwise).
     */
    def future: Future[Done] = f

    /**
     * This future completes successfully once the underlying entity stream has been
     * successfully drained (and fails otherwise).
     */
    def completionStage: CompletionStage[Done] = f.asJava
  }

  /** Adds Scala DSL idiomatic methods to [[HttpEntity]], e.g. versions of methods with an implicit [[Materializer]]. */
  implicit final class HttpEntityScalaDSLSugar(val httpEntity: HttpEntity) extends AnyVal {
    /**
     * Discards the entities data bytes by running the `dataBytes` Source contained in this `entity`.
     *
     * Note: It is crucial that entities are either discarded, or consumed by running the underlying [[akka.stream.scaladsl.Source]]
     * as otherwise the lack of consuming of the data will trigger back-pressure to the underlying TCP connection
     * (as designed), however possibly leading to an idle-timeout that will close the connection, instead of
     * just having ignored the data.
     *
     * Warning: It is not allowed to discard and/or consume the `entity.dataBytes` more than once
     * as the stream is directly attached to the "live" incoming data source from the underlying TCP connection.
     * Allowing it to be consumable twice would require buffering the incoming data, thus defeating the purpose
     * of its streaming nature. If the dataBytes source is materialized a second time, it will fail with an
     * "stream can cannot be materialized more than once" exception.
     *
     * In future versions, more automatic ways to warn or resolve these situations may be introduced, see issue #18716.
     */
    def discardBytes()(implicit mat: Materializer): HttpMessage.DiscardedEntity =
      httpEntity.discardBytes(mat)
  }
}
