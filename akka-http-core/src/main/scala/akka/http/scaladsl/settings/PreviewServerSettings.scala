/*
 * Copyright (C) 2017-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.settings

import akka.annotation.{ ApiMayChange, DoNotInherit }
import akka.http.impl.settings.PreviewServerSettingsImpl
import com.typesafe.config.Config

/**
 * Public API but not intended for subclassing
 *
 * Options that are in "preview" or "early access" mode.
 * These options may change and/or be removed within patch releases
 * without early notice (e.g. by moving them into a stable supported place).
 */
@ApiMayChange @DoNotInherit
abstract class PreviewServerSettings private[akka] () extends akka.http.javadsl.settings.PreviewServerSettings {
  self: PreviewServerSettingsImpl =>

  @deprecated(message = "Use ServerSettings.http2Enabled instead", since = "10.5.0")
  override def enableHttp2: Boolean

  // --

  // override for more specific return type
  @deprecated(message = "Use ServerSettings.withHttp2Enabled instead", since = "10.5.0")
  override def withEnableHttp2(newValue: Boolean): PreviewServerSettings = self.copy(enableHttp2 = newValue)

}

object PreviewServerSettings extends SettingsCompanion[PreviewServerSettings] {
  def fromSubConfig(root: Config, c: Config) =
    PreviewServerSettingsImpl.fromSubConfig(root, c)
  override def apply(config: Config): PreviewServerSettings = PreviewServerSettingsImpl(config)
  override def apply(configOverrides: String): PreviewServerSettings = PreviewServerSettingsImpl(configOverrides)
}
