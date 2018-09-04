/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http
package javadsl
package unmarshalling
package sse

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.javadsl.model.HttpEntity
import akka.http.javadsl.model.sse.ServerSentEvent
import akka.http.scaladsl.model.sse
import akka.http.scaladsl.settings.ServerSentEventSettings
import akka.http.scaladsl.unmarshalling.FromEntityUnmarshaller
import akka.stream.javadsl.Source
import akka.stream.scaladsl

/**
 * Using `fromEventsStream` lets a `HttpEntity` with a `text/event-stream` media type be unmarshalled to a source of
 * `ServerSentEvent`s.
 */
object EventStreamUnmarshalling {

  /**
   * Lets an `HttpEntity` with a `text/event-stream` media type be unmarshalled to a source of `ServerSentEvent`s.
   */
  def fromEventsStream(implicit system: ActorSystem): Unmarshaller[HttpEntity, Source[ServerSentEvent, NotUsed]] = {
    fromEventsStream(ServerSentEventSettings(system))
  }

  /**
   * Lets an `HttpEntity` with a `text/event-stream` media type be unmarshalled to a source of `ServerSentEvent`s.
   * @param settings overrides the default unmarshalling behavior.
   */
  def fromEventsStream(settings: ServerSentEventSettings): Unmarshaller[HttpEntity, Source[ServerSentEvent, NotUsed]] =
    asHttpEntityUnmarshaller(akka.http.scaladsl.unmarshalling.sse.EventStreamUnmarshalling.fromEventsStream(settings))

  // for binary-compatibility, since 10.1.7
  @deprecated("Binary compatibility method. Invocations should have an implicit ActorSystem in scope to provide access to configuration", "10.1.8")
  val fromEventStream: Unmarshaller[HttpEntity, Source[ServerSentEvent, NotUsed]] =
    asHttpEntityUnmarshaller(akka.http.scaladsl.unmarshalling.sse.EventStreamUnmarshalling.fromEventStream)

  private def asHttpEntityUnmarshaller(value: FromEntityUnmarshaller[scaladsl.Source[sse.ServerSentEvent, NotUsed]]): Unmarshaller[HttpEntity, Source[ServerSentEvent, NotUsed]] = {
    value
      .map(_.map(_.asInstanceOf[ServerSentEvent]).asJava)
      .asInstanceOf[Unmarshaller[HttpEntity, Source[ServerSentEvent, NotUsed]]]
  }

}
