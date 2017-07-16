/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http
package javadsl
package unmarshalling
package sse

import akka.NotUsed
import akka.http.javadsl.model.HttpEntity
import akka.http.javadsl.model.sse.ServerSentEvent
import akka.stream.javadsl.Source
import akka.util.Timeout

/**
 * Using `fromEventStream` lets a `HttpEntity` with a `text/event-stream` media type be unmarshalled to a source of
 * `ServerSentEvent`s.
 */
object EventStreamUnmarshalling {

  /**
   * Lets a `HttpEntity` with a `text/event-stream` media type be unmarshalled to a source of `ServerSentEvent`s.
   */
  @deprecated("Use fromEventStreamWithTimeout", "10.0.10")
  val fromEventStream: Unmarshaller[HttpEntity, Source[ServerSentEvent, NotUsed]] =
    scaladsl.unmarshalling.sse.EventStreamUnmarshalling.fromEventStream
      .map(_.map(_.asInstanceOf[ServerSentEvent]).asJava)
      .asInstanceOf[Unmarshaller[HttpEntity, Source[ServerSentEvent, NotUsed]]]

  /**
   * Lets a `HttpEntity` with a `text/event-stream` media type be unmarshalled to a source of `ServerSentEvent`s.
   *
   * @param idleTimeout How long the connection should be allowed to be idle for before the connection is failed.
   */
  def fromEventStreamWithTimeout(idleTimeout: Timeout) = {
    scaladsl.unmarshalling.sse.EventStreamUnmarshalling.fromEventStreamWithTimeout(idleTimeout)
      .map(_.map(_.asInstanceOf[ServerSentEvent]).asJava)
      .asInstanceOf[Unmarshaller[HttpEntity, Source[ServerSentEvent, NotUsed]]]
  }

  /**
   * Lets a `HttpEntity` with a `text/event-stream` media type be unmarshalled to a source of `ServerSentEvent`s.
   *
   * Will shutdown the connection if the server doesn't send any events or heartbeats for 60 seconds.
   */
  def fromEventStreamWithTimeout() = {
    scaladsl.unmarshalling.sse.EventStreamUnmarshalling.fromEventStreamWithTimeout
      .map(_.map(_.asInstanceOf[ServerSentEvent]).asJava)
      .asInstanceOf[Unmarshaller[HttpEntity, Source[ServerSentEvent, NotUsed]]]
  }
}
