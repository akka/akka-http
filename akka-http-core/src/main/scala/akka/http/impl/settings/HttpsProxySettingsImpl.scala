/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.settings

import akka.annotation.InternalApi
import akka.http.impl.util.SettingsCompanion
import com.typesafe.config.Config

/** INTERNAL API */
@InternalApi
private[http] final case class HttpsProxySettingsImpl(
  host: String,
  port: Int
) extends akka.http.scaladsl.settings.HttpsProxySettings {
  require(host != "undefined", "host must not be 'undefined'")
  require(port > 0, "port must be greater than 0")

  override def productPrefix = "HttpsProxySettings"
}

object HttpsProxySettingsImpl extends SettingsCompanion[HttpsProxySettingsImpl]("akka.http.client.proxy.https") {
  override def fromSubConfig(root: Config, c: Config) = {
    new HttpsProxySettingsImpl(
      c getString "host",
      c getInt "port"
    )
  }
}
