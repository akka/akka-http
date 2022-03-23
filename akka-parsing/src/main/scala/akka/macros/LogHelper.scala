/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.macros

import akka.annotation.InternalApi
import akka.event.LoggingAdapter

import scala.reflect.macros.blackbox

/**
 * INTERNAL API
 *
 * Provides access to a LoggingAdapter which each call guarded by `if (log.isXXXEnabled)` to prevent evaluating
 * the message expression eagerly.
 */
@InternalApi
private[akka] trait LogHelper {
  def log: LoggingAdapter
  def isDebugEnabled: Boolean = log.isDebugEnabled
  def isInfoEnabled: Boolean = log.isInfoEnabled
  def isWarningEnabled: Boolean = log.isWarningEnabled

  /** Override to prefix every log message with a user-defined context string */
  def prefixString: String = ""

  def debug(msg: String): Unit = macro LogHelper.debugMacro
  def info(msg: String): Unit = macro LogHelper.infoMacro
  def warning(msg: String): Unit = macro LogHelper.warningMacro
}

/** INTERNAL API */
@InternalApi
private[akka] object LogHelper {
  type LoggerContext = blackbox.Context { type PrefixType = LogHelper }

  def debugMacro(ctx: LoggerContext)(msg: ctx.Expr[String]): ctx.Expr[Unit] =
    ctx.universe.reify {
      {
        val logHelper = ctx.prefix.splice
        if (logHelper.isDebugEnabled)
          logHelper.log.debug(logHelper.prefixString + msg.splice)
      }
    }
  def infoMacro(ctx: LoggerContext)(msg: ctx.Expr[String]): ctx.Expr[Unit] =
    ctx.universe.reify {
      {
        val logHelper = ctx.prefix.splice
        if (logHelper.isInfoEnabled)
          logHelper.log.info(logHelper.prefixString + msg.splice)
      }
    }
  def warningMacro(ctx: LoggerContext)(msg: ctx.Expr[String]): ctx.Expr[Unit] =
    ctx.universe.reify {
      {
        val logHelper = ctx.prefix.splice
        if (logHelper.isWarningEnabled)
          logHelper.log.warning(logHelper.prefixString + msg.splice)
      }
    }
}
