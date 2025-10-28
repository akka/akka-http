/*
 * Copyright (C) 2017-2025 Lightbend Inc. <https://akka.io>
 */

package akka.http.javadsl.settings

import akka.annotation.{ ApiMayChange, DoNotInherit }
import akka.http.impl.settings.ServerSentEventSettingsImpl

/**
 * Public API but not intended for subclassing
 *
 * Options that are in "preview" or "early access" mode.
 * These options may change and/or be removed within patch releases
 * without early notice (e.g. by moving them into a stable supported place).
 */
@ApiMayChange @DoNotInherit
abstract class ServerSentEventSettings private[akka] () { self: ServerSentEventSettingsImpl =>

  /**
   * The maximum size for parsing server-sent events
   */
  def maxEventSize: Int

  /**
   * The maximum size for parsing lines of a server-sent event
   */
  def maxLineSize: Int

  /**
   * Should events with empty data field be passed to the application.
   */
  def emitEmptyEvents: Boolean

  // ---

  def withMaxEventSize(newValue: Int): ServerSentEventSettings = self.copy(maxEventSize = newValue)
  def withLineLength(newValue: Int): ServerSentEventSettings = self.copy(maxLineSize = newValue)
  def withEmitEmptyEvents(newValue: Boolean): ServerSentEventSettings = self.copy(emitEmptyEvents = newValue)
}

