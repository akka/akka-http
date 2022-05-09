package akka.http.caching.impl.settings

import akka.annotation.InternalApi
import akka.http.caching.scaladsl.RefreshingCacheSettings
import akka.http.impl.util._
import com.typesafe.config.Config

import scala.jdk.DurationConverters._
import scala.concurrent.duration.Duration

/** INTERNAL API */
@InternalApi
private[http] final case class RefreshingCacheSettingsImpl(
  maxCapacity:       Int,
  refreshAfterWrite: Duration,
  expireAfterWrite:  Duration
)
  extends RefreshingCacheSettings {
  override def productPrefix = "RefreshingCacheSettings"
}

/** INTERNAL API */
@InternalApi
private[http] object RefreshingCacheSettingsImpl extends SettingsCompanionImpl[RefreshingCacheSettingsImpl]("akka.http.caching.refreshing-cache") {
  def fromSubConfig(root: Config, inner: Config): RefreshingCacheSettingsImpl = {
    val c = inner.withFallback(root.getConfig(prefix))
    new RefreshingCacheSettingsImpl(
      c.getInt("max-capacity"),
      c.getDuration("refresh-after-write").toScala,
      c.getPotentiallyInfiniteDuration("expire-after-write"),

    )
  }
}
