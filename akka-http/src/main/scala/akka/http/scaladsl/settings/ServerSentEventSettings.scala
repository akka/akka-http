/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.settings

import akka.annotation.{ ApiMayChange, DoNotInherit }
import akka.http.impl.settings.ServerSentEventSettingsImpl
import com.typesafe.config.Config

/**
 * Public API but not intended for subclassing
 *
 * Options that are in "preview" or "early access" mode.
 * These options may change and/or be removed within patch releases
 * without early notice (e.g. by moving them into a stable supported place).
 */
@ApiMayChange @DoNotInherit
abstract class ServerSentEventSettings private[akka] () extends akka.http.javadsl.settings.ServerSentEventSettings {
  self: ServerSentEventSettingsImpl ⇒

  override def maxEventSize: Int
  override def maxLineSize: Int

  override def withMaxEventSize(newValue: Int): ServerSentEventSettings = self.copy(maxEventSize = newValue)
  override def withLineLength(newValue: Int): ServerSentEventSettings = self.copy(maxLineSize = newValue)

}

object ServerSentEventSettings extends SettingsCompanion[ServerSentEventSettings] {
  def fromSubConfig(root: Config, c: Config): ServerSentEventSettings =
    ServerSentEventSettingsImpl.fromSubConfig(root, c)
  override def apply(config: Config): ServerSentEventSettings = ServerSentEventSettingsImpl(config)
  override def apply(configOverrides: String): ServerSentEventSettings = ServerSentEventSettingsImpl(configOverrides)
}
