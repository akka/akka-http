/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.model

import akka.annotation.InternalApi
import akka.stream.{ Attributes, FlowShape, Inlet, Outlet }
import akka.stream.stage._
import akka.util.ByteString

import scala.concurrent.duration.FiniteDuration

package ws {

  /** INTERNAL API */
  @InternalApi
  private[ws] class BinaryToStrict(timeout: FiniteDuration, maxBytes: Long) extends GraphStage[FlowShape[ByteString, BinaryMessage.Strict]] {
    private val byteStringIn = Inlet[ByteString]("BinaryToStrict.byteStringIn")
    private val strictMessageOut = Outlet[BinaryMessage.Strict]("BinaryToStrict.strictMessageOut")

    override def initialAttributes: Attributes = Attributes.name("BinaryToStrict")

    override val shape = FlowShape(byteStringIn, strictMessageOut)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new TimerGraphStageLogic(shape) {
      private val builder = ByteString.newBuilder
      private var emptyStream = false

      override def preStart(): Unit = scheduleOnce("BinaryToStrictTimeoutTimer", timeout)

      setHandler(strictMessageOut, new OutHandler {
        override def onPull(): Unit = {
          if (emptyStream) {
            push(strictMessageOut, BinaryMessage.Strict(ByteString.empty))
            completeStage()
          } else pull(byteStringIn)
        }
      })

      setHandler(byteStringIn, new InHandler {
        override def onPush(): Unit = {
          builder ++= grab(byteStringIn)
          if (builder.length > maxBytes) {
            failStage(new WebSocketStreamException(new ErrorInfo("WebSocket message too large", s"Message was larger than the maximum of $maxBytes")))
          } else pull(byteStringIn)
        }

        override def onUpstreamFinish(): Unit = {
          if (isAvailable(strictMessageOut)) {
            push(strictMessageOut, BinaryMessage.Strict(builder.result()))
            completeStage()
          } else emptyStream = true
        }
      })

      override def onTimer(key: Any): Unit =
        failStage(new java.util.concurrent.TimeoutException(s"The stream has not been completed in $timeout for WebSocket message."))
    }

    override def toString = "BinaryToStrict"
  }

  /** INTERNAL API */
  @InternalApi
  private[ws] class TextToStrict(timeout: FiniteDuration, maxBytes: Long) extends GraphStage[FlowShape[String, TextMessage.Strict]] {
    private val stringIn = Inlet[String]("TextToStrict.stringIn")
    private val strictMessageOut = Outlet[TextMessage.Strict]("TextToStrict.strictMessageOut")

    override def initialAttributes: Attributes = Attributes.name("TextToStrict")

    override val shape = FlowShape(stringIn, strictMessageOut)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new TimerGraphStageLogic(shape) {
      private val builder = StringBuilder.newBuilder
      private var byteSize: Int = 0
      private var emptyStream = false

      override def preStart(): Unit = scheduleOnce("TextToStrictTimeoutTimer", timeout)

      setHandler(strictMessageOut, new OutHandler {
        override def onPull(): Unit = {
          if (emptyStream) {
            push(strictMessageOut, TextMessage.Strict(""))
            completeStage()
          } else pull(stringIn)
        }
      })

      setHandler(stringIn, new InHandler {
        override def onPush(): Unit = {
          val in = grab(stringIn)
          builder ++= in
          byteSize += in.getBytes.length
          if (byteSize > maxBytes) {
            failStage(new WebSocketStreamException(new ErrorInfo("WebSocket message too large", s"Message was larger than the maximum of $maxBytes")))
          } else pull(stringIn)
        }

        override def onUpstreamFinish(): Unit = {
          if (isAvailable(strictMessageOut)) {
            push(strictMessageOut, TextMessage.Strict(builder.result()))
            completeStage()
          } else emptyStream = true
        }
      })

      override def onTimer(key: Any): Unit =
        failStage(new java.util.concurrent.TimeoutException(s"The stream has not been completed in $timeout for WebSocket message."))
    }

    override def toString = "TextToStrict"
  }
}
