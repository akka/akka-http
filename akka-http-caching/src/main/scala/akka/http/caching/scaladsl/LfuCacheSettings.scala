/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.caching.scaladsl

import akka.annotation.DoNotInherit
import akka.http.caching.impl.settings.LfuCachingSettingsImpl
import akka.http.caching.javadsl
import akka.http.scaladsl.settings.SettingsCompanion
import com.typesafe.config.Config

import scala.concurrent.duration.Duration

/**
 * Public API but not intended for subclassing
 */
@DoNotInherit
abstract class LfuCacheSettings private[http] () extends javadsl.LfuCacheSettings { self: LfuCachingSettingsImpl =>
  def maxCapacity: Int
  def initialCapacity: Int
  def timeToLive: Duration
  def timeToIdle: Duration

  final def getMaxCapacity: Int = maxCapacity
  final def getInitialCapacity: Int = initialCapacity
  final def getTimeToLive: Duration = timeToLive
  final def getTimeToIdle: Duration = timeToIdle

  override def withMaxCapacity(newMaxCapacity: Int): LfuCacheSettings = self.copy(maxCapacity = newMaxCapacity)
  override def withInitialCapacity(newInitialCapacity: Int): LfuCacheSettings = self.copy(initialCapacity = newInitialCapacity)
  override def withTimeToLive(newTimeToLive: Duration): LfuCacheSettings = self.copy(timeToLive = newTimeToLive)
  override def withTimeToIdle(newTimeToIdle: Duration): LfuCacheSettings = self.copy(timeToIdle = newTimeToIdle)
}

object LfuCacheSettings extends SettingsCompanion[LfuCacheSettings] {
  def apply(config: Config): LfuCacheSettings = LfuCachingSettingsImpl(config)
  def apply(configOverrides: String): LfuCacheSettings = LfuCachingSettingsImpl(configOverrides)
}
