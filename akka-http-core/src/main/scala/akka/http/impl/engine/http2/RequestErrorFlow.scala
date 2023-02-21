/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.NotUsed
import akka.annotation.InternalApi
import akka.http.impl.engine.http2.RequestParsing.ParseRequestResult
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.StatusCodes
import akka.stream.Attributes
import akka.stream.BidiShape
import akka.stream.Inlet
import akka.stream.Outlet
import akka.stream.scaladsl.BidiFlow
import akka.stream.stage.GraphStage
import akka.stream.stage.GraphStageLogic
import akka.stream.stage.InHandler
import akka.stream.stage.OutHandler

/**
 * INTERNAL API
 */
@InternalApi
private[http2] object RequestErrorFlow {

  private val _bidiFlow = BidiFlow.fromGraph(new RequestErrorFlow)
  def apply(): BidiFlow[HttpResponse, HttpResponse, ParseRequestResult, HttpRequest, NotUsed] = _bidiFlow

}

/**
 * INTERNAL API
 */
@InternalApi
private[http2] final class RequestErrorFlow extends GraphStage[BidiShape[HttpResponse, HttpResponse, ParseRequestResult, HttpRequest]] {

  val requestIn = Inlet[ParseRequestResult]("requestIn")
  val requestOut = Outlet[HttpRequest]("requestOut")
  val responseIn = Inlet[HttpResponse]("responseIn")
  val responseOut = Outlet[HttpResponse]("responseOut")

  override val shape: BidiShape[HttpResponse, HttpResponse, ParseRequestResult, HttpRequest] = BidiShape(responseIn, responseOut, requestIn, requestOut)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    setHandlers(requestIn, requestOut, new InHandler with OutHandler {
      override def onPush(): Unit = {
        grab(requestIn) match {
          case RequestParsing.OkRequest(request) => push(requestOut, request)
          case notOk: RequestParsing.BadRequest =>
            emit(responseOut, HttpResponse(StatusCodes.BadRequest, entity = notOk.info.summary).addAttribute(Http2.streamId, notOk.streamId))
            pull(requestIn)
        }
      }

      override def onPull(): Unit = pull(requestIn)
    })
    setHandlers(responseIn, responseOut, new InHandler with OutHandler {
      override def onPush(): Unit = push(responseOut, grab(responseIn))
      override def onPull(): Unit = if (!hasBeenPulled(responseIn)) pull(responseIn)
    })

  }
}
