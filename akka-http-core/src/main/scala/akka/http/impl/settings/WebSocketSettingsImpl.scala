/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.settings

import java.util.Random

import akka.annotation.InternalApi
import akka.http.impl.engine.ws.Randoms
import akka.http.impl.util._
import akka.util.ByteString
import com.typesafe.config.Config

import scala.concurrent.duration.Duration

/** INTERNAL API */
@InternalApi
private[akka] final case class WebSocketSettingsImpl(
  randomFactory:            () => Random,
  periodicKeepAliveMode:    String,
  periodicKeepAliveMaxIdle: Duration,
  periodicKeepAliveData:    () => ByteString)
  extends akka.http.scaladsl.settings.WebSocketSettings {

  require(
    WebSocketSettingsImpl.KeepAliveModes contains periodicKeepAliveMode,
    s"Unsupported keep-alive mode detected! Was [$periodicKeepAliveMode], yet only: ${WebSocketSettingsImpl.KeepAliveModes} are supported.")

  override def productPrefix = "WebSocketSettings"

}

/** INTERNAL API */
@InternalApi
private[akka] object WebSocketSettingsImpl { // on purpose not extending SettingsCompanion since this setting object exists on both server/client side

  private val KeepAliveModes = Seq("ping", "pong")

  // constant value used to identity check and avoid invoking the generation function if no data payload needed
  private val NoPeriodicKeepAliveData = () => ByteString.empty
  def hasNoCustomPeriodicKeepAliveData(settings: akka.http.javadsl.settings.WebSocketSettings): Boolean =
    settings.asInstanceOf[WebSocketSettingsImpl].periodicKeepAliveData eq NoPeriodicKeepAliveData

  def serverFromRoot(root: Config): WebSocketSettingsImpl =
    server(root.getConfig("akka.http.server.websocket"))
  def server(config: Config): WebSocketSettingsImpl =
    fromConfig(config)

  def clientFromRoot(root: Config): WebSocketSettingsImpl =
    client(root.getConfig("akka.http.client.websocket"))
  def client(config: Config): WebSocketSettingsImpl =
    fromConfig(config)

  private def fromConfig(inner: Config): WebSocketSettingsImpl = {
    val c = inner
    new WebSocketSettingsImpl(
      Randoms.SecureRandomInstances,
      c.getString("periodic-keep-alive-mode"), // mode could be extended to be a factory of pings, if we'd need control over the data field
      c.getPotentiallyInfiniteDuration("periodic-keep-alive-max-idle"),
      NoPeriodicKeepAliveData
    )
  }

}

