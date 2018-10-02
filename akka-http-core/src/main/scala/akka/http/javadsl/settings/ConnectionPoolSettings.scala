/*
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.settings

import akka.actor.ActorSystem
import akka.annotation.{ ApiMayChange, DoNotInherit }
import akka.http.impl.settings.ConnectionPoolSettingsImpl
import com.typesafe.config.Config

import scala.concurrent.duration.Duration
import akka.http.impl.util.JavaMapping.Implicits._
import akka.http.javadsl.ClientTransport

@ApiMayChange
trait PoolImplementation
@ApiMayChange
object PoolImplementation {
  def Legacy: PoolImplementation = akka.http.scaladsl.settings.PoolImplementation.Legacy
  def New: PoolImplementation = akka.http.scaladsl.settings.PoolImplementation.New
}

/**
 * Public API but not intended for subclassing
 */
@DoNotInherit
abstract class ConnectionPoolSettings private[akka] () { self: ConnectionPoolSettingsImpl ⇒
  def getMaxConnections: Int = maxConnections
  def getMinConnections: Int = minConnections
  def getMaxRetries: Int = maxRetries
  def getMaxOpenRequests: Int = maxOpenRequests
  def getPipeliningLimit: Int = pipeliningLimit
  def getIdleTimeout: Duration = idleTimeout
  def getConnectionSettings: ClientConnectionSettings = connectionSettings

  @ApiMayChange
  def getPoolImplementation: PoolImplementation = poolImplementation

  @ApiMayChange
  def getResponseEntitySubscriptionTimeout: Duration = responseEntitySubscriptionTimeout

  /**
   * The underlying transport used to connect to hosts. By default [[ClientTransport.TCP]] is used.
   */
  @deprecated("Deprecated in favor of getConnectionSettings.getTransport.", "10.1.0")
  @Deprecated
  def getTransport: ClientTransport = transport.asJava

  // ---

  @ApiMayChange
  def withHostOverrides(hostOverrides: java.util.List[(String, ConnectionPoolSettings)]): ConnectionPoolSettings = {
    import scala.collection.JavaConverters._
    self.copy(hostOverrides = hostOverrides.asScala.toList.map { case (h, s) ⇒ ConnectionPoolSettingsImpl.hostRegex(h) -> s.asScala })
  }

  @ApiMayChange
  def appendHostOverride(hostPattern: String, settings: ConnectionPoolSettings): ConnectionPoolSettings = self.copy(hostOverrides = hostOverrides :+ (ConnectionPoolSettingsImpl.hostRegex(hostPattern) -> settings.asScala))

  def withMaxConnections(n: Int): ConnectionPoolSettings = self.copy(maxConnections = n, hostOverrides = hostOverrides.map { case (k, v) ⇒ k -> v.withMaxConnections(n) })
  def withMinConnections(n: Int): ConnectionPoolSettings = self.copy(minConnections = n, hostOverrides = hostOverrides.map { case (k, v) ⇒ k -> v.withMinConnections(n) })
  def withMaxRetries(n: Int): ConnectionPoolSettings = self.copy(maxRetries = n, hostOverrides = hostOverrides.map { case (k, v) ⇒ k -> v.withMaxRetries(n) })
  def withMaxOpenRequests(newValue: Int): ConnectionPoolSettings = self.copy(maxOpenRequests = newValue, hostOverrides = hostOverrides.map { case (k, v) ⇒ k -> v.withMaxOpenRequests(newValue) })
  def withPipeliningLimit(newValue: Int): ConnectionPoolSettings = self.copy(pipeliningLimit = newValue, hostOverrides = hostOverrides.map { case (k, v) ⇒ k -> v.withPipeliningLimit(newValue) })
  def withIdleTimeout(newValue: Duration): ConnectionPoolSettings = self.copy(idleTimeout = newValue, hostOverrides = hostOverrides.map { case (k, v) ⇒ k -> v.withIdleTimeout(newValue) })
  def withConnectionSettings(newValue: ClientConnectionSettings): ConnectionPoolSettings = self.copy(connectionSettings = newValue.asScala, hostOverrides = hostOverrides.map { case (k, v) ⇒ k -> v.withConnectionSettings(newValue).asScala })

  @ApiMayChange
  def withPoolImplementation(newValue: PoolImplementation): ConnectionPoolSettings = self.copy(poolImplementation = newValue.asScala, hostOverrides = hostOverrides.map { case (k, v) ⇒ k -> v.withPoolImplementation(newValue).asScala })

  @ApiMayChange
  def withResponseEntitySubscriptionTimeout(newValue: Duration): ConnectionPoolSettings = self.copy(responseEntitySubscriptionTimeout = newValue, hostOverrides = hostOverrides.map { case (k, v) ⇒ k -> v.withResponseEntitySubscriptionTimeout(newValue) })

  def withTransport(newValue: ClientTransport): ConnectionPoolSettings = withUpdatedConnectionSettings(_.withTransport(newValue.asScala))
}

object ConnectionPoolSettings extends SettingsCompanion[ConnectionPoolSettings] {
  override def create(config: Config): ConnectionPoolSettings = ConnectionPoolSettingsImpl(config)
  override def create(configOverrides: String): ConnectionPoolSettings = ConnectionPoolSettingsImpl(configOverrides)
  override def create(system: ActorSystem): ConnectionPoolSettings = create(system.settings.config)
}
