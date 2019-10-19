/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.settings

import java.util.Random
import java.util.function.Supplier

import akka.actor.ActorSystem
import akka.annotation.DoNotInherit
import akka.http.impl.settings.WebSocketSettingsImpl
import akka.util.ByteString
import com.typesafe.config.Config

import scala.concurrent.duration.Duration

/**
 * Public API but not intended for subclassing
 */
@DoNotInherit
trait WebSocketSettings { self: WebSocketSettingsImpl =>
  def getRandomFactory: Supplier[Random]
  def periodicKeepAliveMode: String
  def periodicKeepAliveMaxIdle: Duration
  /**
   * The provided supplier will be invoked for each new keep-alive frame that is sent.
   * The ByteString will be included in the Ping or Pong frame sent as heartbeat,
   * so keep in mind to keep it relatively small, in order not to make the frames too bloated.
   */
  def getPeriodicKeepAliveData: Supplier[ByteString]

  def withRandomFactoryFactory(newValue: Supplier[Random]): WebSocketSettings =
    copy(randomFactory = () => newValue.get())
  def withPeriodicKeepAliveMode(newValue: String): WebSocketSettings =
    copy(periodicKeepAliveMode = newValue)
  def withPeriodicKeepAliveMaxIdle(newValue: Duration): WebSocketSettings =
    copy(periodicKeepAliveMaxIdle = newValue)
  def withPeriodicKeepAliveData(newValue: Supplier[ByteString]): WebSocketSettings =
    copy(periodicKeepAliveData = () => newValue.get())
}

object WebSocketSettings {
  def server(config: Config): WebSocketSettings = WebSocketSettingsImpl.server(config)
  def server(system: ActorSystem): WebSocketSettings = server(system.settings.config)

  def client(config: Config): WebSocketSettings = WebSocketSettingsImpl.client(config)
  def client(system: ActorSystem): WebSocketSettings = client(system.settings.config)
}
