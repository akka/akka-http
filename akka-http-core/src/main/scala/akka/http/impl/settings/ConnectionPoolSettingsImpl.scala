/*
 * Copyright (C) 2017-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.settings

import akka.annotation.InternalApi
import akka.http.impl.util._
import akka.http.scaladsl.settings._
import com.typesafe.config.Config

import scala.collection.immutable
import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration
import scala.util.matching.Regex

/** INTERNAL API */
@InternalApi
private[akka] final case class ConnectionPoolSettingsImpl(
  maxConnections:                    Int,
  minConnections:                    Int,
  maxRetries:                        Int,
  maxOpenRequests:                   Int,
  pipeliningLimit:                   Int,
  maxConnectionLifetime:             Duration,
  baseConnectionBackoff:             FiniteDuration,
  maxConnectionBackoff:              FiniteDuration,
  idleTimeout:                       Duration,
  connectionSettings:                ClientConnectionSettings,
  responseEntitySubscriptionTimeout: Duration,
  hostOverrides:                     immutable.Seq[(Regex, ConnectionPoolSettings)])
  extends ConnectionPoolSettings {

  require(maxConnections > 0, "max-connections must be > 0")
  require(minConnections >= 0, "min-connections must be >= 0")
  require(minConnections <= maxConnections, "min-connections must be <= max-connections")
  require(maxRetries >= 0, "max-retries must be >= 0")
  require(maxOpenRequests > 0, "max-open-requests must be a power of 2 > 0.")
  require((maxOpenRequests & (maxOpenRequests - 1)) == 0, "max-open-requests must be a power of 2. " + suggestPowerOfTwo(maxOpenRequests))
  require(pipeliningLimit > 0, "pipelining-limit must be > 0")
  require(maxConnectionLifetime > Duration.Zero, "max-connection-lifetime must be > 0")
  require(idleTimeout >= Duration.Zero, "idle-timeout must be >= 0")
  require(
    minConnections == 0 || (baseConnectionBackoff.toMillis > 0 && maxConnectionBackoff.toMillis > 10),
    "If min-connections > 0, you need to set a base-connection-backoff must be > 0 and max-connection-backoff must be > 10 millis " +
      "to avoid client pools excessively trying to open up new connections.")
  require(hostOverrides.isEmpty || hostOverrides.forall(_._2.hostOverrides.isEmpty), "host-overrides should not be nested")

  override def productPrefix = "ConnectionPoolSettings"

  def withUpdatedConnectionSettings(f: ClientConnectionSettings => ClientConnectionSettings): ConnectionPoolSettingsImpl =
    copy(connectionSettings = f(connectionSettings), hostOverrides = hostOverrides.map { case (k, v) => k -> v.withUpdatedConnectionSettings(f) })

  private def suggestPowerOfTwo(around: Int): String = {
    val firstBit = 31 - Integer.numberOfLeadingZeros(around)

    val below = 1 << firstBit
    val above = 1 << (firstBit + 1)

    s"Perhaps try $below or $above."
  }

  /** INTERNAL API */
  private[http] def copyDeep(
    mapHostOverrides:                  ConnectionPoolSettings => ConnectionPoolSettings,
    maxConnections:                    Int                                              = maxConnections,
    minConnections:                    Int                                              = minConnections,
    maxRetries:                        Int                                              = maxRetries,
    maxOpenRequests:                   Int                                              = maxOpenRequests,
    pipeliningLimit:                   Int                                              = pipeliningLimit,
    maxConnectionLifetime:             Duration                                         = maxConnectionLifetime,
    baseConnectionBackoff:             FiniteDuration                                   = baseConnectionBackoff,
    maxConnectionBackoff:              FiniteDuration                                   = maxConnectionBackoff,
    idleTimeout:                       Duration                                         = idleTimeout,
    connectionSettings:                ClientConnectionSettings                         = connectionSettings,
    responseEntitySubscriptionTimeout: Duration                                         = responseEntitySubscriptionTimeout): ConnectionPoolSettings =
    copy(
      maxConnections,
      minConnections,
      maxRetries,
      maxOpenRequests,
      pipeliningLimit,
      maxConnectionLifetime,
      baseConnectionBackoff,
      maxConnectionBackoff,
      idleTimeout,
      connectionSettings,
      responseEntitySubscriptionTimeout,
      hostOverrides = hostOverrides.map { case (k, v) => k -> mapHostOverrides(v) })

}

/** INTERNAL API */
@InternalApi
private[akka] object ConnectionPoolSettingsImpl extends SettingsCompanionImpl[ConnectionPoolSettingsImpl]("akka.http.host-connection-pool") {

  def fromSubConfig(root: Config, c: Config): ConnectionPoolSettingsImpl = {
    new ConnectionPoolSettingsImpl(
      c.getInt("max-connections"),
      c.getInt("min-connections"),
      c.getInt("max-retries"),
      c.getInt("max-open-requests"),
      c.getInt("pipelining-limit"),
      c.getPotentiallyInfiniteDuration("max-connection-lifetime"),
      c.getFiniteDuration("base-connection-backoff"),
      c.getFiniteDuration("max-connection-backoff"),
      c.getPotentiallyInfiniteDuration("idle-timeout"),
      ClientConnectionSettingsImpl.fromSubConfig(root, c.getConfig("client")),
      c getPotentiallyInfiniteDuration "response-entity-subscription-timeout",
      List.empty
    )
  }

  private[akka] def hostRegex(pattern: String): Regex = {
    val regexPattern = if (pattern.startsWith("regex:")) {
      pattern.stripPrefix("regex:")
    } else {
      pattern.stripPrefix("glob:").map {
        case '*'   => ".*"
        case '?'   => "."
        case '.'   => "\\."
        case other => other.toString
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

}
