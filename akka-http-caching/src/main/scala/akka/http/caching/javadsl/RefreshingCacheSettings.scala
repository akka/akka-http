/*
 * Copyright (C) 2017-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.caching.javadsl

import akka.annotation.DoNotInherit
import akka.http.caching.impl.settings.RefreshingCacheSettingsImpl
import akka.http.javadsl.settings.SettingsCompanion
import com.typesafe.config.Config

import scala.concurrent.duration.Duration

/**
 * Public API but not intended for subclassing
 */
@DoNotInherit
abstract class RefreshingCacheSettings private[http] () { self: RefreshingCacheSettingsImpl =>

  /* JAVA APIs */
  def getMaxCapacity: Int
  def getRefreshAfterWrite: Duration
  def getExpireAfterWrite: Duration

  def withMaxCapacity(newMaxCapacity: Int): RefreshingCacheSettings = self.copy(maxCapacity = newMaxCapacity)
  def withRefreshAfterWrite(newWriteAfterRefresh: Duration): RefreshingCacheSettings = self.copy(refreshAfterWrite = newWriteAfterRefresh)
  def withExpireAfterWrite(newExpireAfterRefresh: Duration): RefreshingCacheSettings = self.copy(expireAfterWrite = newExpireAfterRefresh)

}

object RefreshingCacheSettings extends SettingsCompanion[RefreshingCacheSettings] {
  def create(config: Config): RefreshingCacheSettings = RefreshingCacheSettingsImpl(config)
  def create(configOverrides: String): RefreshingCacheSettings = RefreshingCacheSettingsImpl(configOverrides)
}
