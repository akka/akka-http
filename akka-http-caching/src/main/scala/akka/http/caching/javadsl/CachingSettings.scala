/*
 * Copyright (C) 2017-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.caching.javadsl

import akka.actor.ActorSystem
import akka.annotation.DoNotInherit
import akka.http.caching.impl.settings.CachingSettingsImpl
import akka.http.javadsl.settings.SettingsCompanion
import com.typesafe.config.Config

/**
 * Public API but not intended for subclassing
 */
@DoNotInherit
abstract class CachingSettings private[http] () { self: CachingSettingsImpl =>
  def lfuCacheSettings: LfuCacheSettings

  // overloads for idiomatic Scala use
  def withLfuCacheSettings(newSettings: LfuCacheSettings): CachingSettings = {
    import akka.http.impl.util.JavaMapping.Implicits._
    import akka.http.caching.CacheJavaMapping.Implicits._

    self.copy(lfuCacheSettings = newSettings.asScala)
  }
}

object CachingSettings extends SettingsCompanion[CachingSettings] {
  def create(config: Config): CachingSettings = CachingSettingsImpl(config)
  def create(configOverrides: String): CachingSettings = CachingSettingsImpl(configOverrides)
  override def create(system: ActorSystem): CachingSettings = create(system.settings.config)
}
