/*
 * Copyright (C) 2017-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.caching.impl.settings

import akka.annotation.InternalApi
import akka.http.caching.scaladsl.LoadingCacheSettings
import akka.http.impl.util._
import com.typesafe.config.Config

import scala.concurrent.duration.Duration

/** INTERNAL API */
@InternalApi
private[http] final case class LoadingCacheSettingsImpl(
  maxCapacity:       Int,
  refreshAfterWrite: Duration,
  expireAfterWrite:  Duration
)
  extends LoadingCacheSettings {
  override def productPrefix = "RefreshingCacheSettings"
}

/** INTERNAL API */
@InternalApi
private[http] object LoadingCacheSettingsImpl extends SettingsCompanionImpl[LoadingCacheSettingsImpl]("akka.http.caching.refreshing-cache") {
  def fromSubConfig(root: Config, inner: Config): LoadingCacheSettingsImpl = {
    val c = inner.withFallback(root.getConfig(prefix))
    new LoadingCacheSettingsImpl(
      c.getInt("max-capacity"),
      c.getPotentiallyInfiniteDuration("refresh-after-write"),
      c.getPotentiallyInfiniteDuration("expire-after-write"),

    )
  }
}
