/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http
package scaladsl
package unmarshalling
package sse

import akka.NotUsed
import akka.actor.ActorSystem
import akka.annotation.ApiMayChange
import akka.http.impl.settings.ServerSentEventSettingsImpl
import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.model.MediaTypes.`text/event-stream`
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.settings.ServerSentEventSettings
import akka.stream.scaladsl.{ Keep, Source }

/**
 * Importing [[EventStreamUnmarshalling.fromEventsStream]] lets an `HttpEntity` with a `text/event-stream` media type be
 * unmarshalled to a source of [[ServerSentEvent]]s.
 *
 * The maximum size for parsing server-sent events is 8KiB. The maximum size for parsing lines of a server-sent event
 * is 4KiB. If you need to customize any of these, set the `akka.http.sse.max-event-size` and
 * `akka.http.sse.max-line-size` properties respectively.
 */
@ApiMayChange
object EventStreamUnmarshalling extends EventStreamUnmarshalling

/**
 * Mixing in this trait lets a `HttpEntity` with a `text/event-stream` media type be unmarshalled to a source of
 * [[ServerSentEvent]]s.
 *
 * The maximum size for parsing server-sent events is 8KiB by default and can be customized by configuring
 * `akka.http.sse.max-event-size`. The maximum size for parsing lines of a server-sent event is 4KiB by
 * default and can be customized by configuring `akka.http.sse.max-line-size`.
 */
@ApiMayChange
trait EventStreamUnmarshalling {

  /**
   * The maximum size for parsing lines of a server-sent event; 4KiB by default.
   */
  @deprecated("Set this property in configuration as `akka.http.sse.max-line-size` before calling fromEventsStream(implicit ActorSystem)", "10.1.8")
  protected def maxLineSize: Int = 4096

  /**
   * The maximum size for parsing server-sent events; 8KiB by default.
   */
  @deprecated("Set this property in configuration as `akka.http.sse.max-event-size` before calling fromEventsStream(implicit ActorSystem)", "10.1.8")
  protected def maxEventSize: Int = 8192

  @deprecated("Binary compatibility method. Invocations should have an implicit ActorSystem in scope to provide access to configuration", "10.1.8")
  final val fromEventStream: FromEntityUnmarshaller[Source[ServerSentEvent, NotUsed]] =
    fromEventsStream(maxEventSize, maxLineSize)

  /**
   * Lets an `HttpEntity` with a `text/event-stream` media type be unmarshalled to a source of `ServerSentEvent`s.
   */
  implicit final def fromEventsStream(implicit system: ActorSystem): FromEntityUnmarshaller[Source[ServerSentEvent, NotUsed]] = {
    fromEventsStream(ServerSentEventSettingsImpl(system))
  }

  /**
   * Lets an `HttpEntity` with a `text/event-stream` media type be unmarshalled to a source of `ServerSentEvent`s.
   * @param settings overrides the default unmarshalling behavior.
   */
  def fromEventsStream(settings: ServerSentEventSettings): FromEntityUnmarshaller[Source[ServerSentEvent, NotUsed]] = {
    fromEventsStream(settings.maxLineSize, settings.maxEventSize)
  }

  private final def fromEventsStream(maxLineSize: Int, maxEventSize: Int): FromEntityUnmarshaller[Source[ServerSentEvent, NotUsed]] = {
    val eventStreamParser = EventStreamParser(maxLineSize, maxEventSize)
    def unmarshal(entity: HttpEntity) =
      entity
        .withoutSizeLimit // Because of streaming: the server keeps the response open and potentially streams huge amounts of data
        .dataBytes
        .viaMat(eventStreamParser)(Keep.none)
    Unmarshaller.strict(unmarshal).forContentTypes(`text/event-stream`)
  }
}
