/*
 * Copyright (C) 2017-2024 Lightbend Inc. <https://www.lightbend.com>
 *
 * Copied and adapted from akka-remote
 * https://github.com/akka/akka/blob/c90121485fcfc44a3cee62a0c638e1982d13d812/akka-remote/src/main/scala/akka/remote/artery/StageLogging.scala
 */

package akka.http.impl.util

import akka.annotation.InternalApi
import akka.stream.stage.GraphStageLogic
import akka.event.LoggingAdapter

// TODO Try to reconcile with what Akka provides in StageLogging.
// We thought this could be removed when https://github.com/akka/akka/issues/18793 had been implemented
// but we need a few more changes to be able to override the default logger. So for now we keep it here.
/**
 * INTERNAL API
 */
@InternalApi
private[akka] trait StageLoggingWithOverride extends GraphStageLogic {
  def logOverride: LoggingAdapter = DefaultNoLogging

  private var _log: LoggingAdapter = null

  protected def logSource: Class[AnyRef] = this.getClass.asInstanceOf[Class[AnyRef]]

  def log: LoggingAdapter = {
    // only used in StageLogic, i.e. thread safe
    _log match {
      case null =>
        _log =
          logOverride match {
            case DefaultNoLogging => akka.event.Logging(materializer.system, logSource)
            case x                => x
          }
      case _ =>
    }
    _log
  }
}

/**
 * INTERNAL API
 *
 * A copy of NoLogging that can be used as a place-holder for "logging not explicitly specified".
 * It can be matched on to be overridden with default behavior.
 */
@InternalApi
private[akka] object DefaultNoLogging extends LoggingAdapter {
  /**
   * Java API to return the reference to NoLogging
   * @return The NoLogging instance
   */
  def getInstance = this

  final override def isErrorEnabled = false
  final override def isWarningEnabled = false
  final override def isInfoEnabled = false
  final override def isDebugEnabled = false

  final protected override def notifyError(message: String): Unit = ()
  final protected override def notifyError(cause: Throwable, message: String): Unit = ()
  final protected override def notifyWarning(message: String): Unit = ()
  final protected override def notifyInfo(message: String): Unit = ()
  final protected override def notifyDebug(message: String): Unit = ()
}
