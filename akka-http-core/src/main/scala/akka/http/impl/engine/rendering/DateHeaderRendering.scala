/*
 * Copyright (C) 2021-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.rendering

import akka.actor.{ ClassicActorSystemProvider, Scheduler }
import akka.annotation.InternalApi
import akka.http.impl.util.Rendering.CrLf
import akka.http.impl.util.{ ByteArrayRendering, StringRendering }
import akka.http.scaladsl.model.{ DateTime, headers }

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

/** INTERNAL API */
@InternalApi private[http] trait DateHeaderRendering {
  def renderHeaderPair(): (String, String)
  def renderHeaderBytes(): Array[Byte]
  def renderHeaderValue(): String
}

/** INTERNAL API */
@InternalApi private[http] object DateHeaderRendering {
  def apply(now: () => Long = () => System.currentTimeMillis())(implicit system: ClassicActorSystemProvider): DateHeaderRendering =
    apply(system.classicSystem.scheduler, now)(system.classicSystem.dispatcher)

  def apply(scheduler: Scheduler, now: () => Long)(implicit ec: ExecutionContext): DateHeaderRendering = {
    def renderValue(): String =
      DateTime(now()).renderRfc1123DateTimeString(new StringRendering).get

    sealed trait DateState
    /** Date has not been used for a while */
    case object Idle extends DateState
    case class AutoUpdated(value: String) extends DateState {
      var wasUsed: Boolean = false
      val headerPair: (String, String) = "date" -> value
      val headerBytes: Array[Byte] = (new ByteArrayRendering(48) ~~ headers.Date ~~ value ~~ CrLf).get
    }
    val dateState = new AtomicReference[DateState](Idle)
    val updateInterval = 1.second
    def scheduleAutoUpdate(): Unit =
      try scheduler.scheduleOnce(updateInterval)(autoUpdate())
      catch {
        case _: IllegalStateException =>
          // can fail during shutdown, no need to be noisy here
          dateState.set(Idle)
      }

    def autoUpdate(): Unit =
      dateState.get() match {
        case a: AutoUpdated =>
          if (a.wasUsed) {
            dateState.set(AutoUpdated(renderValue()))
            scheduleAutoUpdate()
          } else
            dateState.set(Idle) // wasn't retrieved, no reason to continue autoupdating
        case Idle => new IllegalStateException("Should not happen, invariant is either state == Idle or scheduled both never both")
      }

    def get(rendered: String): AutoUpdated =
      dateState.get() match {
        case a: AutoUpdated =>
          // might not be instantly visible on updater thread
          // which might prevent automatic rescheduling in the worst case
          a.wasUsed = true
          a
        case Idle =>
          val r = if (rendered ne null) rendered else renderValue()
          val newValue = AutoUpdated(r)
          newValue.wasUsed = true
          // use CAS to avoid that multiple accessing threads schedule multiple timers
          if (!dateState.compareAndSet(Idle, newValue)) get(rendered)
          else {
            scheduleAutoUpdate()
            newValue
          }
      }

    new DateHeaderRendering {
      override def renderHeaderPair(): (String, String) = get(null).headerPair
      override def renderHeaderBytes(): Array[Byte] = get(null).headerBytes
      override def renderHeaderValue(): String = get(null).value
    }
  }

  val Unavailable = new DateHeaderRendering {
    override def renderHeaderPair(): (String, String) = throw new IllegalStateException("DateHeaderRendering is not available here")
    override def renderHeaderBytes(): Array[Byte] = throw new IllegalStateException("DateHeaderRendering is not available here")
    override def renderHeaderValue(): String = throw new IllegalStateException("DateHeaderRendering is not available here")
  }
}
