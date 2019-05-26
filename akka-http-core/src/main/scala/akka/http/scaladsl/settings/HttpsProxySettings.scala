/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.settings

import akka.annotation.DoNotInherit
import akka.http.impl.settings.HttpsProxySettingsImpl
import com.typesafe.config.Config

/**
 * Public API but not intended for subclassing
 */
@DoNotInherit
abstract class HttpsProxySettings private[akka] extends akka.http.javadsl.settings.HttpsProxySettings() { self: HttpsProxySettingsImpl =>
  def host: String
  def port: Int
}

object HttpsProxySettings extends SettingsCompanion[HttpsProxySettings] {
  override def apply(config: Config): HttpsProxySettings = HttpsProxySettingsImpl(config)
  override def apply(configOverrides: String): HttpsProxySettings = HttpsProxySettingsImpl(configOverrides)
}

