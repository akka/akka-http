/*
 * Copyright (C) 2017-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.caching.scaladsl

import akka.annotation.DoNotInherit
import akka.http.caching.impl.settings.LoadingCacheSettingsImpl
import akka.http.caching.javadsl
import akka.http.scaladsl.settings.SettingsCompanion
import com.typesafe.config.Config

import scala.concurrent.duration.Duration

/**
 * Public API but not intended for subclassing
 */
@DoNotInherit
abstract class LoadingCacheSettings private[http] () extends javadsl.LoadingCacheSettings { self: LoadingCacheSettingsImpl =>
  def maxCapacity: Int
  def refreshAfterWrite: Duration
  def expireAfterWrite: Duration

  final def getMaxCapacity: Int = maxCapacity
  final def getRefreshAfterWrite: Duration = refreshAfterWrite
  final def getExpireAfterWrite: Duration = expireAfterWrite

  override def withMaxCapacity(newMaxCapacity: Int): LoadingCacheSettings = self.copy(maxCapacity = newMaxCapacity)
  override def withRefreshAfterWrite(newRefreshAfterWrite: Duration): LoadingCacheSettings = self.copy(refreshAfterWrite = newRefreshAfterWrite)
  override def withExpireAfterWrite(newExpireAfterWrite: Duration): LoadingCacheSettings = self.copy(expireAfterWrite = newExpireAfterWrite)

}

object LoadingCacheSettings extends SettingsCompanion[LoadingCacheSettings] {
  def apply(config: Config): LoadingCacheSettings = LoadingCacheSettingsImpl(config)
  def apply(configOverrides: String): LoadingCacheSettings = LoadingCacheSettingsImpl(configOverrides)
}
