/*
 * Copyright (C) 2017-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.settings

import akka.actor.ActorSystem
import akka.annotation.DoNotInherit
import akka.http.impl.settings.HttpsProxySettingsImpl
import com.typesafe.config.Config

/**
 * Public API but not intended for subclassing
 */
@DoNotInherit
abstract class HttpsProxySettings private[akka] () { self: HttpsProxySettingsImpl =>
  def getHost: String = host
  def getPort: Int = port
}

object HttpsProxySettings extends SettingsCompanion[HttpsProxySettings] {
  override def create(config: Config): HttpsProxySettings = HttpsProxySettingsImpl(config)
  override def create(configOverrides: String): HttpsProxySettings = HttpsProxySettingsImpl(configOverrides)
  override def create(system: ActorSystem): HttpsProxySettings = create(system.settings.config)
}
