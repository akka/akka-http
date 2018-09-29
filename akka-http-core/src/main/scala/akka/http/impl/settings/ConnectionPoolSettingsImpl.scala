/*
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.settings

import akka.annotation.InternalApi
import akka.http.impl.util.{ SettingsCompanion, _ }
import akka.http.scaladsl.settings.{ ClientConnectionSettings, ConnectionPoolSettings, PoolImplementation }
import com.typesafe.config.Config

import scala.collection.immutable
import scala.concurrent.duration.Duration
import scala.util.matching.Regex

/** INTERNAL API */
@InternalApi
private[akka] final case class ConnectionPoolSettingsImpl(
  maxConnections:                    Int,
  minConnections:                    Int,
  maxRetries:                        Int,
  maxOpenRequests:                   Int,
  pipeliningLimit:                   Int,
  idleTimeout:                       Duration,
  connectionSettings:                ClientConnectionSettings,
  poolImplementation:                PoolImplementation,
  responseEntitySubscriptionTimeout: Duration,
  hostMap:                           immutable.Seq[(Regex, ConnectionPoolSettings)])
  extends ConnectionPoolSettings {

  require(maxConnections > 0, "max-connections must be > 0")
  require(minConnections >= 0, "min-connections must be >= 0")
  require(minConnections <= maxConnections, "min-connections must be <= max-connections")
  require(maxRetries >= 0, "max-retries must be >= 0")
  require(maxOpenRequests > 0, "max-open-requests must be a power of 2 > 0.")
  require((maxOpenRequests & (maxOpenRequests - 1)) == 0, "max-open-requests must be a power of 2. " + suggestPowerOfTwo(maxOpenRequests))
  require(pipeliningLimit > 0, "pipelining-limit must be > 0")
  require(idleTimeout >= Duration.Zero, "idle-timeout must be >= 0")

  override def productPrefix = "ConnectionPoolSettings"

  def withUpdatedConnectionSettings(f: ClientConnectionSettings ⇒ ClientConnectionSettings): ConnectionPoolSettingsImpl =
    copy(connectionSettings = f(connectionSettings))

  private def suggestPowerOfTwo(around: Int): String = {
    val firstBit = 31 - Integer.numberOfLeadingZeros(around)

    val below = 1 << firstBit
    val above = 1 << (firstBit + 1)

    s"Perhaps try $below or $above."
  }
}

object ConnectionPoolSettingsImpl extends SettingsCompanion[ConnectionPoolSettingsImpl]("akka.http.host-connection-pool") {

  private[akka] def hostRegex(pattern: String): Regex = {
    val regexPattern = if (pattern.startsWith("regex:")) {
      pattern.stripPrefix("regex:")
    } else {
      pattern.stripPrefix("glob:").map {
        case '*'   ⇒ ".*"
        case '?'   ⇒ "."
        case '.'   ⇒ "\\."
        case other ⇒ other.toString
      }.mkString
    }

    // If the pattern starts with a wildcard, we want to match subdomains, as well as the raw domain
    // so *.example.com should match www.example.com as well as example.com. So we replacing any leading
    // (.*\.) pattern to allow for the beginning of the string, as well as an arbitrary subdomain. But it
    // won't match something like thisexample.com
    val p = if (regexPattern.startsWith(".*\\.")) {
      s"(^|.*\\.)${regexPattern.drop(4)}"
    } else {
      regexPattern
    }

    p.r

  }

  def fromSubConfig(root: Config, c: Config) = {
    new ConnectionPoolSettingsImpl(
      c getInt "max-connections",
      c getInt "min-connections",
      c getInt "max-retries",
      c getInt "max-open-requests",
      c getInt "pipelining-limit",
      c getPotentiallyInfiniteDuration "idle-timeout",
      ClientConnectionSettingsImpl.fromSubConfig(root, c.getConfig("client")),
      c.getString("pool-implementation").toLowerCase match {
        case "legacy" ⇒ PoolImplementation.Legacy
        case "new"    ⇒ PoolImplementation.New
      },
      c getPotentiallyInfiniteDuration "response-entity-subscription-timeout",
      List.empty
    )
  }

}
