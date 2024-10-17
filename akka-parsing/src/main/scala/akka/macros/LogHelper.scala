/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.macros

import akka.annotation.InternalApi
//import akka.event.LoggingAdapter

/**
 * INTERNAL API
 *
 * Provides access to a LoggingAdapter which each call guarded by `if (log.isXXXEnabled)` to prevent evaluating
 * the message expression eagerly.
 */
@InternalApi
private[akka] trait LogHelper extends LogHelperMacro {
  def log: akka.event.LoggingAdapter
  def isDebugEnabled: Boolean = log.isDebugEnabled
  def isInfoEnabled: Boolean = log.isInfoEnabled
  def isWarningEnabled: Boolean = log.isWarningEnabled

  /** Override to prefix every log message with a user-defined context string */
  def prefixString: String = ""
}

