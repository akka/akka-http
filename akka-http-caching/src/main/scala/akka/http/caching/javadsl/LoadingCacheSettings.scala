/*
 * Copyright (C) 2017-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.caching.javadsl

import akka.annotation.DoNotInherit
import akka.http.caching.impl.settings.LoadingCacheSettingsImpl
import akka.http.javadsl.settings.SettingsCompanion
import com.typesafe.config.Config

import scala.concurrent.duration.Duration

/**
 * Public API but not intended for subclassing
 */
@DoNotInherit
abstract class LoadingCacheSettings private[http] () { self: LoadingCacheSettingsImpl =>

  /* JAVA APIs */
  def getMaxCapacity: Int
  def getRefreshAfterWrite: Duration
  def getExpireAfterWrite: Duration

  def withMaxCapacity(newMaxCapacity: Int): LoadingCacheSettings = self.copy(maxCapacity = newMaxCapacity)
  def withRefreshAfterWrite(newWriteAfterRefresh: Duration): LoadingCacheSettings = self.copy(refreshAfterWrite = newWriteAfterRefresh)
  def withExpireAfterWrite(newExpireAfterRefresh: Duration): LoadingCacheSettings = self.copy(expireAfterWrite = newExpireAfterRefresh)

}

object LoadingCacheSettings extends SettingsCompanion[LoadingCacheSettings] {
  def create(config: Config): LoadingCacheSettings = LoadingCacheSettingsImpl(config)
  def create(configOverrides: String): LoadingCacheSettings = LoadingCacheSettingsImpl(configOverrides)
}
