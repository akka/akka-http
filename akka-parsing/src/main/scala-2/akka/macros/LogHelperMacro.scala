/*
 * Copyright (C) 2021-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.macros

import akka.annotation.InternalApi

import scala.reflect.macros.blackbox

/** INTERNAL API */
@InternalApi
private[akka] trait LogHelperMacro {
  def debug(msg: String): Unit = macro LogHelperMacro.debugMacro
  def info(msg: String): Unit = macro LogHelperMacro.infoMacro
  def warning(msg: String): Unit = macro LogHelperMacro.warningMacro
}

/** INTERNAL API */
@InternalApi
private[akka] object LogHelperMacro {
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
