/*
 * Copyright (C) 2017-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.caching.scaladsl

import akka.annotation.DoNotInherit
import akka.http.caching.impl.settings.CachingSettingsImpl
import akka.http.caching.javadsl
import akka.http.scaladsl.settings.SettingsCompanion
import com.typesafe.config.Config

/**
 * Public API but not intended for subclassing
 */
@DoNotInherit
abstract class CachingSettings private[http] () extends javadsl.CachingSettings { self: CachingSettingsImpl =>
  def lfuCacheSettings: LfuCacheSettings

  // overloads for idiomatic Scala use
  def withLfuCacheSettings(newSettings: LfuCacheSettings): CachingSettings =
    self.copy(lfuCacheSettings = newSettings)
}

object CachingSettings extends SettingsCompanion[CachingSettings] {
  def apply(config: Config): CachingSettings = CachingSettingsImpl(config)
  def apply(configOverrides: String): CachingSettings = CachingSettingsImpl(configOverrides)
}
