/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http
package scaladsl
package unmarshalling
package sse

import java.util.concurrent.TimeoutException

import akka.{ Done, NotUsed }
import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.model.MediaTypes.`text/event-stream`
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.stream.scaladsl.{ Sink, Source }
import java.util.{ List ⇒ JList }

import akka.util.{ ByteString, Timeout }
import org.scalatest.{ AsyncWordSpec, Matchers }

import scala.collection.JavaConverters
import scala.collection.immutable.Seq
import scala.concurrent.duration._

object EventStreamUnmarshallingSpec {

  val events: Seq[ServerSentEvent] =
    1.to(666).map(n ⇒ ServerSentEvent(n.toString))

  // Also used by EventStreamUnmarshallingTest.java
  val eventsAsJava: JList[javadsl.model.sse.ServerSentEvent] = {
    import JavaConverters._
    events.map(_.asInstanceOf[javadsl.model.sse.ServerSentEvent]).asJava
  }

  // Also used by EventStreamUnmarshallingTest.java
  val entity: HttpEntity =
    HttpEntity(`text/event-stream`, Source(events).map(_.encode))
}

final class EventStreamUnmarshallingSpec extends AsyncWordSpec with Matchers with BaseUnmarshallingSpec {
  import EventStreamUnmarshallingSpec._
  import akka.http.scaladsl.unmarshalling.sse.EventStreamUnmarshalling._

  "A HTTP entity with media-type text/event-stream" should {
    "be unmarshallable to an EventStream" in {
      Unmarshal(entity)
        .to[Source[ServerSentEvent, NotUsed]]
        .flatMap(_.runWith(Sink.seq))
        .map(_ shouldBe events)
    }

    "timeout if the server doesn't send any heartbeats" in {
      implicit val timeout = Timeout(500.millis)
      Unmarshal(HttpEntity(`text/event-stream`, Source.maybe))
        .to[Source[ServerSentEvent, NotUsed]]
        .flatMap(_.runWith(Sink.ignore))
        .map(_ ⇒ "failed")
        .recover {
          case _: TimeoutException ⇒ "succeeded"
        }.map(_ shouldBe "succeeded")
    }

    "not timeout if the server does send heartbeats" in {
      implicit val timeout = Timeout(500.millis)
      Unmarshal(HttpEntity(
        `text/event-stream`,
        Source.tick(200.millis, 200.millis, ByteString("\r\n"))
          .take(4)
      )).to[Source[ServerSentEvent, NotUsed]]
        .flatMap(_.runWith(Sink.ignore))
        .map(_ shouldBe Done)
    }

  }
}
