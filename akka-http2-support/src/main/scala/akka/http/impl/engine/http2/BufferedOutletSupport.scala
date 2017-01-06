/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.stream.Outlet
import akka.stream.stage.GraphStageLogic
import akka.stream.stage.OutHandler

trait BufferedOutletSupport { logic: GraphStageLogic ⇒
  trait GenericOutlet[T] {
    def setHandler(handler: OutHandler): Unit
    def push(elem: T): Unit
    def canBePushed: Boolean
  }
  object GenericOutlet {
    implicit def fromSubSourceOutlet[T](subSourceOutlet: SubSourceOutlet[T]): GenericOutlet[T] =
      new GenericOutlet[T] {
        def setHandler(handler: OutHandler): Unit = subSourceOutlet.setHandler(handler)
        def push(elem: T): Unit = subSourceOutlet.push(elem)
        def canBePushed: Boolean = subSourceOutlet.isAvailable
      }
    implicit def fromOutlet[T](outlet: Outlet[T]): GenericOutlet[T] =
      new GenericOutlet[T] {
        def setHandler(handler: OutHandler): Unit = logic.setHandler(outlet, handler)
        def push(elem: T): Unit = logic.emit(outlet, elem)
        def canBePushed: Boolean = logic.isAvailable(outlet)
      }
  }
  class BufferedOutlet[T](outlet: GenericOutlet[T]) extends OutHandler {
    val buffer: java.util.ArrayDeque[T] = new java.util.ArrayDeque[T]

    /**
     * override to hook into actually pushing, e.g. to keep track how much
     * has been pushed already (in contract, to being still cached)
     */
    protected def doPush(elem: T): Unit = outlet.push(elem)

    override def onPull(): Unit = {
      if (!buffer.isEmpty) {
        val popped = buffer.pop()
        doPush(popped)
      }
    }
    outlet.setHandler(this)

    def push(elem: T): Unit =
      if (outlet.canBePushed && buffer.isEmpty) doPush(elem)
      else buffer.addLast(elem)

    def tryFlush(): Unit = {
      if (outlet.canBePushed && !buffer.isEmpty)
        doPush(buffer.pop())
    }
  }

  class BufferedOutletExtended[T](outlet: GenericOutlet[T]) extends OutHandler {
    case class ElementAndTrigger(element: T, trigger: () ⇒ Unit)
    val buffer: java.util.ArrayDeque[ElementAndTrigger] = new java.util.ArrayDeque[ElementAndTrigger]

    /**
     * override to hook into actually pushing, e.g. to keep track how much
     * has been pushed already (in contract, to being still cached)
     */
    protected def doPush(elem: ElementAndTrigger): Unit = {
      outlet.push(elem.element)
      elem.trigger()
    }

    override def onPull(): Unit =
      if (!buffer.isEmpty) doPush(buffer.pop())

    outlet.setHandler(this)

    final def push(element: T): Unit = pushWithTrigger(element, () ⇒ ())
    final def pushWithTrigger(elem: T, trigger: () ⇒ Unit): Unit =
      if (outlet.canBePushed && buffer.isEmpty) doPush(ElementAndTrigger(elem, trigger))
      else buffer.addLast(ElementAndTrigger(elem, trigger))

    def tryFlush(): Unit =
      if (outlet.canBePushed && !buffer.isEmpty)
        doPush(buffer.pop())
  }
}
