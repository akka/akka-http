/*
 * Copyright (C) 2017-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.settings

import akka.actor.ActorSystem
import akka.annotation.{ ApiMayChange, DoNotInherit }
import akka.http.impl.settings.ConnectionPoolSettingsImpl
import akka.http.impl.util.JavaMapping.Implicits._
import akka.http.javadsl.ClientTransport
import com.typesafe.config.Config

import scala.concurrent.duration.{ Duration, FiniteDuration }

/**
 * Public API but not intended for subclassing
 */
@DoNotInherit
abstract class ConnectionPoolSettings private[akka] () { self: ConnectionPoolSettingsImpl =>
  def getMaxConnections: Int = maxConnections
  def getMinConnections: Int = minConnections
  def getMaxRetries: Int = maxRetries
  def getMaxOpenRequests: Int = maxOpenRequests
  def getPipeliningLimit: Int = pipeliningLimit
  def getBaseConnectionBackoff: FiniteDuration = baseConnectionBackoff
  def getMaxConnectionBackoff: FiniteDuration = maxConnectionBackoff
  def getIdleTimeout: Duration = idleTimeout
  def getConnectionSettings: ClientConnectionSettings = connectionSettings

  @ApiMayChange
  def getResponseEntitySubscriptionTimeout: Duration = responseEntitySubscriptionTimeout

  // ---

  @ApiMayChange
  def withHostOverrides(hostOverrides: java.util.List[(String, ConnectionPoolSettings)]): ConnectionPoolSettings = {
    import scala.collection.JavaConverters._
    self.copy(hostOverrides = hostOverrides.asScala.toList.map { case (h, s) => ConnectionPoolSettingsImpl.hostRegex(h) -> s.asScala })
  }

  @ApiMayChange
  def appendHostOverride(hostPattern: String, settings: ConnectionPoolSettings): ConnectionPoolSettings = self.copy(hostOverrides = hostOverrides :+ (ConnectionPoolSettingsImpl.hostRegex(hostPattern) -> settings.asScala))

  def withMaxConnections(n: Int): ConnectionPoolSettings = self.copy(maxConnections = n, hostOverrides = hostOverrides.map { case (k, v) => k -> v.withMaxConnections(n) })
  def withMinConnections(n: Int): ConnectionPoolSettings = self.copy(minConnections = n, hostOverrides = hostOverrides.map { case (k, v) => k -> v.withMinConnections(n) })
  def withMaxRetries(n: Int): ConnectionPoolSettings = self.copy(maxRetries = n, hostOverrides = hostOverrides.map { case (k, v) => k -> v.withMaxRetries(n) })
  def withMaxOpenRequests(newValue: Int): ConnectionPoolSettings = self.copy(maxOpenRequests = newValue, hostOverrides = hostOverrides.map { case (k, v) => k -> v.withMaxOpenRequests(newValue) })
  /** Client-side pipelining is not currently supported, see https://github.com/akka/akka-http/issues/32 */
  def withPipeliningLimit(newValue: Int): ConnectionPoolSettings = self.copy(pipeliningLimit = newValue, hostOverrides = hostOverrides.map { case (k, v) => k -> v.withPipeliningLimit(newValue) })
  def withBaseConnectionBackoff(newValue: FiniteDuration): ConnectionPoolSettings = self.copy(baseConnectionBackoff = newValue, hostOverrides = hostOverrides.map { case (k, v) => k -> v.withBaseConnectionBackoff(newValue) })
  def withMaxConnectionBackoff(newValue: FiniteDuration): ConnectionPoolSettings = self.copy(maxConnectionBackoff = newValue, hostOverrides = hostOverrides.map { case (k, v) => k -> v.withMaxConnectionBackoff(newValue) })
  def withIdleTimeout(newValue: Duration): ConnectionPoolSettings = self.copy(idleTimeout = newValue, hostOverrides = hostOverrides.map { case (k, v) => k -> v.withIdleTimeout(newValue) })
  def withMaxConnectionLifetime(newValue: Duration): ConnectionPoolSettings = self.copy(maxConnectionLifetime = newValue, hostOverrides = hostOverrides.map { case (k, v) => k -> v.withMaxConnectionLifetime(newValue) })
  def withConnectionSettings(newValue: ClientConnectionSettings): ConnectionPoolSettings = self.copy(connectionSettings = newValue.asScala, hostOverrides = hostOverrides.map { case (k, v) => k -> v.withConnectionSettings(newValue).asScala })

  @ApiMayChange
  def withResponseEntitySubscriptionTimeout(newValue: Duration): ConnectionPoolSettings = self.copy(responseEntitySubscriptionTimeout = newValue, hostOverrides = hostOverrides.map { case (k, v) => k -> v.withResponseEntitySubscriptionTimeout(newValue) })

  def withTransport(newValue: ClientTransport): ConnectionPoolSettings = withUpdatedConnectionSettings(_.withTransport(newValue.asScala))
}

object ConnectionPoolSettings extends SettingsCompanion[ConnectionPoolSettings] {
  override def create(config: Config): ConnectionPoolSettings = ConnectionPoolSettingsImpl(config)
  override def create(configOverrides: String): ConnectionPoolSettings = ConnectionPoolSettingsImpl(configOverrides)
  override def create(system: ActorSystem): ConnectionPoolSettings = create(system.settings.config)
}
