/*
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.http.caching.javadsl

import akka.actor.ActorSystem
import akka.annotation.DoNotInherit
import akka.http.caching.scaladsl.{ CachingSettingsImpl, LfuCacheSettingsImpl }

import scala.concurrent.duration.Duration

/**
 * Public API but not intended for subclassing
 */
@DoNotInherit
abstract class CachingSettings private[http] () { self: CachingSettingsImpl ⇒
  def lfuCacheSettings: LfuCacheSettings

  def withLfuCacheSettings(newSettings: LfuCacheSettings): CachingSettings
}

/**
 * Public API but not intended for subclassing
 */
@DoNotInherit
abstract class LfuCacheSettings { self: LfuCacheSettingsImpl ⇒
  def getMaxCapacity: Int
  def getInitialCapacity: Int
  def getTimeToLive: Duration
  def getTimeToIdle: Duration

  def withMaxCapacity(newMaxCapacity: Int): LfuCacheSettings
  def withInitialCapacity(newInitialCapacity: Int): LfuCacheSettings
  def withTimeToLive(newTimeToLive: Duration): LfuCacheSettings
  def withTimeToIdle(newTimeToIdle: Duration): LfuCacheSettings
}

object CachingSettings {
  /** Java API: Create default caching settings from the systems' configuration */
  def create(system: ActorSystem): CachingSettings =
    akka.http.caching.scaladsl.CachingSettings(system)
}
