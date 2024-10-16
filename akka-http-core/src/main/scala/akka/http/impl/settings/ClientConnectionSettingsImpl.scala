/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.settings

import java.net.InetSocketAddress
import java.util.Random

import akka.annotation.InternalApi
import akka.http.impl.util._
import akka.http.scaladsl.ClientTransport
import akka.http.scaladsl.model.headers.`User-Agent`
import akka.http.scaladsl.settings.ClientConnectionSettings.LogUnencryptedNetworkBytes
import akka.http.scaladsl.settings.Http2ClientSettings.Http2ClientSettingsImpl
import akka.http.scaladsl.settings.{ Http2ClientSettings, ParserSettings, WebSocketSettings }
import akka.io.Inet.SocketOption
import com.typesafe.config.Config

import scala.collection.immutable
import scala.concurrent.duration.{ Duration, FiniteDuration }
import scala.util.Try

/** INTERNAL API */
@InternalApi
private[akka] final case class ClientConnectionSettingsImpl(
  userAgentHeader:            Option[`User-Agent`],
  connectingTimeout:          FiniteDuration,
  idleTimeout:                Duration,
  requestHeaderSizeHint:      Int,
  logUnencryptedNetworkBytes: Option[Int],
  websocketSettings:          WebSocketSettings,
  socketOptions:              immutable.Seq[SocketOption],
  parserSettings:             ParserSettings,
  streamCancellationDelay:    FiniteDuration,
  localAddress:               Option[InetSocketAddress],
  http2Settings:              Http2ClientSettings,
  transport:                  ClientTransport)
  extends akka.http.scaladsl.settings.ClientConnectionSettings {

  require(connectingTimeout >= Duration.Zero, "connectingTimeout must be >= 0")
  require(requestHeaderSizeHint > 0, "request-size-hint must be > 0")
  require(
    Try { parserSettings.maxContentLength }.isSuccess,
    "The provided ParserSettings is a generic object that does not contain the client-specific settings."
  )
  override def productPrefix = "ClientConnectionSettings"

  override def websocketRandomFactory: () => Random = websocketSettings.randomFactory
}

/** INTERNAL API */
@InternalApi
private[akka] object ClientConnectionSettingsImpl extends SettingsCompanionImpl[ClientConnectionSettingsImpl]("akka.http.client") {
  def fromSubConfig(root: Config, inner: Config): ClientConnectionSettingsImpl = {
    val c = inner.withFallback(root.getConfig(prefix))
    new ClientConnectionSettingsImpl(
      userAgentHeader = c.getString("user-agent-header").toOption.map(`User-Agent`(_)),
      connectingTimeout = c.getFiniteDuration("connecting-timeout"),
      idleTimeout = c.getPotentiallyInfiniteDuration("idle-timeout"),
      requestHeaderSizeHint = c.getIntBytes("request-header-size-hint"),
      logUnencryptedNetworkBytes = LogUnencryptedNetworkBytes(c getString "log-unencrypted-network-bytes"),
      websocketSettings = WebSocketSettingsImpl.client(c.getConfig("websocket")),
      socketOptions = SocketOptionSettings.fromSubConfig(root, c.getConfig("socket-options")),
      parserSettings = ParserSettingsImpl.fromSubConfig(root, c.getConfig("parsing")),
      streamCancellationDelay = c.getFiniteDuration("stream-cancellation-delay"),
      localAddress = None,
      http2Settings = Http2ClientSettingsImpl.fromSubConfig(root, c.getConfig("http2")),
      transport = ClientTransport.TCP)
  }
}
