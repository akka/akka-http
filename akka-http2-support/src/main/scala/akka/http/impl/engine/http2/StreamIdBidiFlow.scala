/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.NotUsed

import akka.http.scaladsl.Http2
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse }
import akka.stream.scaladsl.BidiFlow
import akka.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler }
import akka.stream.{ Attributes, BidiShape, Inlet, Outlet }

import scala.collection.mutable

class StreamIdBidiFlow extends GraphStage[BidiShape[HttpRequest, HttpRequest, HttpResponse, HttpResponse]] {
  val in = Inlet[HttpRequest]("StreamIdBidi.in")
  val out = Outlet[HttpResponse]("StreamIdBidi.out")
  val toWrapped = Outlet[HttpRequest]("StreamIdBidi.toWrapped")
  val fromWrapped = Inlet[HttpResponse]("StreamIdBidi.fromWrapped")

  override def initialAttributes = Attributes.name("StreamIdBidi")

  val shape = BidiShape(in, toWrapped, fromWrapped, out)

  override def toString = "One2OneBidi"

  override def createLogic(effectiveAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    private val idsInFlight = mutable.ArrayDeque[Option[Int]]()

    setHandler(in, new InHandler {
      override def onPush(): Unit = {
        val element = grab(in)
        idsInFlight.prepend(element.attribute(Http2.streamId))
        push(toWrapped, element)
      }
      override def onUpstreamFinish(): Unit = complete(toWrapped)
    })

    setHandler(toWrapped, new OutHandler {
      override def onPull(): Unit = pull(in)
    })

    setHandler(fromWrapped, new InHandler {
      override def onPush(): Unit = {
        val element = grab(fromWrapped)
        idsInFlight.removeLast() match {
          case None     => push(out, element)
          case Some(id) => push(out, element.addAttribute(Http2.streamId, id))
        }
      }
    })

    setHandler(out, new OutHandler {
      override def onPull(): Unit = pull(fromWrapped)
      override def onDownstreamFinish(): Unit = cancel(fromWrapped)
    })
  }
}
object StreamIdBidiFlow {
  def apply(): BidiFlow[HttpRequest, HttpRequest, HttpResponse, HttpResponse, NotUsed] =
    BidiFlow.fromGraph(new StreamIdBidiFlow())
}
