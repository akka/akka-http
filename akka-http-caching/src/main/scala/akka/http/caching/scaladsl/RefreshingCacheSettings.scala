/*
 * Copyright (C) 2017-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.caching.scaladsl

import akka.annotation.DoNotInherit
import akka.http.caching.impl.settings.RefreshingCacheSettingsImpl
import akka.http.caching.javadsl
import akka.http.scaladsl.settings.SettingsCompanion
import com.typesafe.config.Config

import scala.concurrent.duration.Duration

/**
 * Public API but not intended for subclassing
 */
@DoNotInherit
abstract class RefreshingCacheSettings private[http] () extends javadsl.RefreshingCacheSettings { self: RefreshingCacheSettingsImpl =>
  def maxCapacity: Int
  def refreshAfterWrite: Duration
  def expireAfterWrite: Duration

  final def getMaxCapacity: Int = maxCapacity
  final def getRefreshAfterWrite: Duration = refreshAfterWrite
  final def getExpireAfterWrite: Duration = expireAfterWrite

  override def withMaxCapacity(newMaxCapacity: Int): RefreshingCacheSettings = self.copy(maxCapacity = newMaxCapacity)
  override def withRefreshAfterWrite(newRefreshAfterWrite: Duration): RefreshingCacheSettings = self.copy(refreshAfterWrite = newRefreshAfterWrite)
  override def withExpireAfterWrite(newExpireAfterWrite: Duration): RefreshingCacheSettings = self.copy(expireAfterWrite = newExpireAfterWrite)

}

object RefreshingCacheSettings extends SettingsCompanion[RefreshingCacheSettings] {
  def apply(config: Config): RefreshingCacheSettings = RefreshingCacheSettingsImpl(config)
  def apply(configOverrides: String): RefreshingCacheSettings = RefreshingCacheSettingsImpl(configOverrides)
}
