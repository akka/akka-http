/*
 * Copyright (C) 2017-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.settings

import java.time.{ Duration => JDuration }

import com.typesafe.config.Config

import scala.concurrent.duration.{ Duration, FiniteDuration }

import akka.actor.ActorSystem
import akka.annotation.{ ApiMayChange, DoNotInherit }
import akka.http.impl.settings.ConnectionPoolSettingsImpl
import akka.http.impl.util.JavaMapping.Implicits._
import akka.http.javadsl.ClientTransport
import akka.util.JavaDurationConverters._

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
  def getMaxConnectionLifetime: JDuration = maxConnectionLifetime.asJava
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

  def withMaxConnections(n: Int): ConnectionPoolSettings = self.copyDeep(_.withMaxConnections(n), maxConnections = n)
  def withMinConnections(n: Int): ConnectionPoolSettings = self.copyDeep(_.withMinConnections(n), minConnections = n)
  def withMaxRetries(n: Int): ConnectionPoolSettings = self.copyDeep(_.withMaxRetries(n), maxRetries = n)
  def withMaxOpenRequests(newValue: Int): ConnectionPoolSettings = self.copyDeep(_.withMaxOpenRequests(newValue), maxOpenRequests = newValue)
  /** Client-side pipelining is not currently supported, see https://github.com/akka/akka-http/issues/32 */
  def withPipeliningLimit(newValue: Int): ConnectionPoolSettings = self.copyDeep(_.withPipeliningLimit(newValue), pipeliningLimit = newValue)
  def withBaseConnectionBackoff(newValue: FiniteDuration): ConnectionPoolSettings = self.copyDeep(_.withBaseConnectionBackoff(newValue), baseConnectionBackoff = newValue)
  def withMaxConnectionBackoff(newValue: FiniteDuration): ConnectionPoolSettings = self.copyDeep(_.withMaxConnectionBackoff(newValue), maxConnectionBackoff = newValue)
  def withIdleTimeout(newValue: Duration): ConnectionPoolSettings = self.copyDeep(_.withIdleTimeout(newValue), idleTimeout = newValue)
  def withMaxConnectionLifetime(newValue: Duration): ConnectionPoolSettings = self.copyDeep(_.withMaxConnectionLifetime(newValue), maxConnectionLifetime = newValue)
  def withConnectionSettings(newValue: ClientConnectionSettings): ConnectionPoolSettings = self.copyDeep(_.withConnectionSettings(newValue.asScala), connectionSettings = newValue.asScala)

  @ApiMayChange
  def withResponseEntitySubscriptionTimeout(newValue: Duration): ConnectionPoolSettings = self.copyDeep(_.withResponseEntitySubscriptionTimeout(newValue), responseEntitySubscriptionTimeout = newValue)

  def withTransport(newValue: ClientTransport): ConnectionPoolSettings = withUpdatedConnectionSettings(_.withTransport(newValue.asScala))
}

object ConnectionPoolSettings extends SettingsCompanion[ConnectionPoolSettings] {
  override def create(config: Config): ConnectionPoolSettings = ConnectionPoolSettingsImpl(config)
  override def create(configOverrides: String): ConnectionPoolSettings = ConnectionPoolSettingsImpl(configOverrides)
  override def create(system: ActorSystem): ConnectionPoolSettings = create(system.settings.config)
}
