/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.parsing

import akka.NotUsed
import akka.annotation.InternalApi
import akka.http.impl.util.StreamUtils
import akka.http.scaladsl.model._
import akka.stream.scaladsl.Source
import akka.util.ByteString

/**
 * INTERNAL API
 */
@InternalApi
private[http] sealed trait ParserOutput

/**
 * INTERNAL API
 */
@InternalApi
private[http] object ParserOutput {
  sealed trait RequestOutput extends ParserOutput
  sealed trait ResponseOutput extends ParserOutput
  sealed trait MessageStart extends ParserOutput
  sealed trait MessageOutput extends RequestOutput with ResponseOutput
  sealed trait ErrorOutput extends MessageOutput

  final case class RequestStart(
    method:            HttpMethod,
    uri:               Uri,
    protocol:          HttpProtocol,
    headers:           List[HttpHeader],
    createEntity:      EntityCreator[RequestOutput, RequestEntity],
    expect100Continue: Boolean,
    closeRequested:    Boolean) extends MessageStart with RequestOutput

  final case class ResponseStart(
    statusCode:     StatusCode,
    protocol:       HttpProtocol,
    headers:        List[HttpHeader],
    createEntity:   EntityCreator[ResponseOutput, ResponseEntity],
    closeRequested: Boolean) extends MessageStart with ResponseOutput

  case object MessageEnd extends MessageOutput

  final case class EntityPart(data: ByteString) extends MessageOutput

  final case class EntityChunk(chunk: HttpEntity.ChunkStreamPart) extends MessageOutput

  final case class MessageStartError(status: StatusCode, info: ErrorInfo) extends MessageStart with ErrorOutput

  final case class EntityStreamError(info: ErrorInfo) extends ErrorOutput

  //////////// meta messages ///////////

  case object StreamEnd extends MessageOutput

  case object NeedMoreData extends MessageOutput

  case object NeedNextRequestMethod extends ResponseOutput

  final case class RemainingBytes(bytes: ByteString) extends ResponseOutput

  //////////////////////////////////////

  sealed abstract class EntityCreator[-A <: ParserOutput, +B <: HttpEntity] extends (Source[A, NotUsed] ⇒ B)

  /**
   * An entity creator that uses the given entity directly and ignores the passed-in source.
   */
  final case class StrictEntityCreator[-A <: ParserOutput, +B <: HttpEntity](entity: B) extends EntityCreator[A, B] {
    def apply(parts: Source[A, NotUsed]) = {
      // We might need to drain stray empty tail streams which will be read by no one.
      StreamUtils.cancelSource(parts)(StreamUtils.OnlyRunInGraphInterpreterContext) // only called within Http graphs stages
      entity
    }
  }

  /**
   * An entity creator that creates the entity from the a source of parts.
   */
  final case class StreamedEntityCreator[-A <: ParserOutput, +B <: HttpEntity](creator: Source[A, NotUsed] ⇒ B)
    extends EntityCreator[A, B] {
    def apply(parts: Source[A, NotUsed]) = creator(parts)
  }
}
