/*
 * Copyright (C) 2017-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.caching.impl.settings

import akka.annotation.InternalApi
import akka.http.caching.scaladsl.{ CachingSettings, LfuCacheSettings }
import akka.http.impl.util.SettingsCompanionImpl
import com.typesafe.config.Config

/** INTERNAL API */
@InternalApi
private[http] final case class CachingSettingsImpl(lfuCacheSettings: LfuCacheSettings)
  extends CachingSettings {
  override def productPrefix = "CachingSettings"
}

/** INTERNAL API */
@InternalApi
private[http] object CachingSettingsImpl extends SettingsCompanionImpl[CachingSettingsImpl]("akka.http.caching") {
  def fromSubConfig(root: Config, c: Config): CachingSettingsImpl = {
    new CachingSettingsImpl(
      LfuCachingSettingsImpl.fromSubConfig(root, c.getConfig("lfu-cache"))
    )
  }
}
