/*
 * Copyright (C) 2017-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.settings

import akka.actor.ActorSystem
import akka.annotation.{ ApiMayChange, DoNotInherit, InternalApi }
import akka.http.impl.settings.ConnectionPoolSettingsImpl
import akka.http.javadsl.{ settings => js }
import akka.http.scaladsl.ClientTransport
import com.typesafe.config.Config

import scala.collection.immutable
import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration
import scala.util.matching.Regex

/**
 * Public API but not intended for subclassing
 */
@DoNotInherit
abstract class ConnectionPoolSettings extends js.ConnectionPoolSettings { self: ConnectionPoolSettingsImpl =>
  def maxConnections: Int
  def minConnections: Int
  def maxRetries: Int
  def maxOpenRequests: Int
  def pipeliningLimit: Int
  def baseConnectionBackoff: FiniteDuration
  def maxConnectionBackoff: FiniteDuration
  def idleTimeout: Duration
  def connectionSettings: ClientConnectionSettings
  def maxConnectionLifetime: Duration
  private[akka] def hostOverrides: immutable.Seq[(Regex, ConnectionPoolSettings)]

  /**
   * This checks to see if there's a matching host override. `hostMap` will only ever be populated
   * by the default connection pool in the Http extension, so user supplied connection pools will
   * never have any overrides, so will always use the object passed explicitly
   */
  private[akka] def forHost(h: String): ConnectionPoolSettings = {
    hostOverrideImpl.apply(h, this)
  }

  /**
   * The underlying transport used to connect to hosts. By default [[ClientTransport.TCP]] is used.
   */
  @deprecated("Deprecated in favor of connectionSettings.transport", "10.1.0")
  def transport: ClientTransport = connectionSettings.transport

  /** The time after which the pool will drop an entity automatically if it wasn't read or discarded */
  @ApiMayChange
  def responseEntitySubscriptionTimeout: Duration

  // ---

  @ApiMayChange
  def withHostOverrides(hostOverrides: immutable.Seq[(String, ConnectionPoolSettings)]): ConnectionPoolSettings = self.copy(hostOverrides = hostOverrides.map { case (h, s) => ConnectionPoolSettingsImpl.hostRegex(h) -> s })

  @ApiMayChange
  def appendHostOverride(hostPattern: String, settings: ConnectionPoolSettings): ConnectionPoolSettings = self.copy(hostOverrides = hostOverrides :+ (ConnectionPoolSettingsImpl.hostRegex(hostPattern) -> settings))

  override def withMaxConnections(n: Int): ConnectionPoolSettings = self.copyDeep(_.withMaxConnections(n), maxConnections = n)
  override def withMinConnections(n: Int): ConnectionPoolSettings = self.copyDeep(_.withMinConnections(n), minConnections = n)
  override def withMaxRetries(n: Int): ConnectionPoolSettings = self.copyDeep(_.withMaxRetries(n), maxRetries = n)
  override def withMaxOpenRequests(newValue: Int): ConnectionPoolSettings = self.copyDeep(_.withMaxOpenRequests(newValue), maxOpenRequests = newValue)
  override def withBaseConnectionBackoff(newValue: FiniteDuration): ConnectionPoolSettings = self.copyDeep(_.withBaseConnectionBackoff(newValue))
  override def withMaxConnectionBackoff(newValue: FiniteDuration): ConnectionPoolSettings = self.copyDeep(_.withMaxConnectionBackoff(newValue), maxConnectionBackoff = newValue)
  override def withPipeliningLimit(newValue: Int): ConnectionPoolSettings = self.copyDeep(_.withPipeliningLimit(newValue), pipeliningLimit = newValue)
  override def withIdleTimeout(newValue: Duration): ConnectionPoolSettings = self.copyDeep(_.withIdleTimeout(newValue), idleTimeout = newValue)
  override def withMaxConnectionLifetime(newValue: Duration): ConnectionPoolSettings = self.copyDeep(_.withMaxConnectionLifetime(newValue), maxConnectionLifetime = newValue)
  def withConnectionSettings(newValue: ClientConnectionSettings): ConnectionPoolSettings = self.copyDeep(_.withConnectionSettings(newValue), connectionSettings = newValue)

  @ApiMayChange
  override def withResponseEntitySubscriptionTimeout(newValue: Duration): ConnectionPoolSettings = self.copyDeep(_.withResponseEntitySubscriptionTimeout(newValue), responseEntitySubscriptionTimeout = newValue)

  /**
   * Since 10.1.0, the transport is configured in [[ClientConnectionSettings]]. This method is a shortcut for
   * `withUpdatedConnectionSettings(_.withTransport(newTransport))`.
   */
  def withTransport(newValue: ClientTransport): ConnectionPoolSettings =
    withUpdatedConnectionSettings(_.withTransport(newValue))

  def withUpdatedConnectionSettings(f: ClientConnectionSettings => ClientConnectionSettings): ConnectionPoolSettings
}

object ConnectionPoolSettings extends SettingsCompanion[ConnectionPoolSettings] {

  override def apply(config: Config) = ConnectionPoolSettingsImpl(config)

  /**
   * Builds a ConnectionPoolSettings that has the host specific overrides populated from config
   *
   * This is the ONLY place that a connection pool object can be created that has the ability to be configured per
   * host, and it's ONLY used by `HttpExt.defaultConnectionPoolSettings`. The intent is NOT to let users define or use
   * this, or even for akka internally to use this. Think of this more like a placeholder for the
   * `defaultConnectionPoolSettings` provided in the HttpExt.singleRequest and other client methods instead of breaking
   * binary compatibility by making those calls take an `Option[_]`or using `null`
   *
   */
  @ApiMayChange
  def withOverrides(system: ActorSystem): ConnectionPoolSettings = {
    withOverrides(system.settings.config)
  }

  /**
   * Builds a ConnectionPoolSettings that has the host specific overrides populated from config
   *
   * This is the ONLY place that a connection pool object can be created that has the abililty to be configured per
   * host, and it's ONLY used by `HttpExt.defaultConnectionPoolSettings`. The intent is NOT to let users define or use
   * this, or even for akka internally to use this. Think of this more like a placeholder for the
   * `defaultConnectionPoolSettings` provided in the HttpExt.singleRequest and other client methods instead of breaking
   * binary compatibility by making those calls take an `Option[_]`or using `null`
   *
   */
  @ApiMayChange
  def withOverrides(config: Config, overrideImpl: HostOverride = DefaultHostOverride): ConnectionPoolSettings = {
    import scala.collection.JavaConverters._

    val configOverrides = config.getConfigList("akka.http.host-connection-pool.per-host-override").asScala.toList.flatMap { cfg =>
      cfg.root.entrySet().asScala.map { entry =>
        ConnectionPoolSettingsImpl.hostRegex(entry.getKey) ->
          ConnectionPoolSettingsImpl(entry.getValue.atPath("akka.http.host-connection-pool").withFallback(config))
      }
    }

    ConnectionPoolSettingsImpl(config).copy(hostOverrides = configOverrides, hostOverrideImpl = overrideImpl)
  }

  override def apply(configOverrides: String) = ConnectionPoolSettingsImpl(configOverrides)
}
