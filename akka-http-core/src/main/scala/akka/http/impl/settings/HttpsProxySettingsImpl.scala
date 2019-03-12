/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.settings

import akka.annotation.InternalApi
import akka.http.impl.util.SettingsCompanionImpl
import com.typesafe.config.Config

/** INTERNAL API */
@InternalApi
private[http] final case class HttpsProxySettingsImpl(
  host: String,
  port: Int
) extends akka.http.scaladsl.settings.HttpsProxySettings {
  require(host != "", "host must not be left empty")
  require(port > 0, "port must be greater than 0")

  override def productPrefix = "HttpsProxySettings"
}

object HttpsProxySettingsImpl extends SettingsCompanionImpl[HttpsProxySettingsImpl]("akka.http.client.proxy.https") {
  override def fromSubConfig(root: Config, c: Config): HttpsProxySettingsImpl = {
    new HttpsProxySettingsImpl(
      c.getString("host"),
      c.getInt("port")
    )
  }
}
