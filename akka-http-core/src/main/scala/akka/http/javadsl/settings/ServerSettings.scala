/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.settings

import java.util.function.Supplier
import java.util.{ Optional, Random }

import akka.actor.ActorSystem
import akka.annotation.{ DoNotInherit, InternalApi }
import akka.http.impl.settings.ServerSettingsImpl
import akka.http.javadsl.model.headers.Host
import akka.http.javadsl.model.headers.Server
import akka.io.Inet.SocketOption
import akka.http.impl.util.JavaMapping.Implicits._
import com.typesafe.config.Config

import scala.collection.JavaConverters._
import scala.compat.java8.OptionConverters
import scala.compat.java8.OptionConverters._
import scala.concurrent.duration.{ Duration, FiniteDuration }

/**
 * Public API but not intended for subclassing
 */
@DoNotInherit abstract class ServerSettings { self: ServerSettingsImpl =>
  def getServerHeader: Optional[Server]
  def getPreviewServerSettings: PreviewServerSettings
  def getTimeouts: ServerSettings.Timeouts
  def getMaxConnections: Int
  def getPipeliningLimit: Int
  def getRemoteAddressHeader: Boolean
  def getRawRequestUriHeader: Boolean
  def getTransparentHeadRequests: Boolean
  def getVerboseErrorMessages: Boolean
  def getResponseHeaderSizeHint: Int
  def getBacklog: Int
  def getSocketOptions: java.lang.Iterable[SocketOption]
  def getDefaultHostHeader: Host
  def getWebsocketRandomFactory: java.util.function.Supplier[Random]
  def getWebsocketSettings: WebSocketSettings
  def getParserSettings: ParserSettings
  def getLogUnencryptedNetworkBytes: Optional[Int]
  def getHttp2Settings: Http2ServerSettings = self.http2Settings
  def getDefaultHttpPort: Int
  def getDefaultHttpsPort: Int
  def getTerminationDeadlineExceededResponse: akka.http.javadsl.model.HttpResponse
  def getStreamCancellationDelay: FiniteDuration = self.streamCancellationDelay

  // ---

  def withServerHeader(newValue: Optional[Server]): ServerSettings = self.copy(serverHeader = newValue.asScala.map(_.asScala))
  def withPreviewServerSettings(newValue: PreviewServerSettings): ServerSettings = self.copy(previewServerSettings = newValue.asScala)
  def withTimeouts(newValue: ServerSettings.Timeouts): ServerSettings = self.copy(timeouts = newValue.asScala)
  def withMaxConnections(newValue: Int): ServerSettings = self.copy(maxConnections = newValue)
  def withPipeliningLimit(newValue: Int): ServerSettings = self.copy(pipeliningLimit = newValue)
  def withRemoteAddressHeader(newValue: Boolean): ServerSettings = self.copy(remoteAddressHeader = newValue)
  def withRawRequestUriHeader(newValue: Boolean): ServerSettings = self.copy(rawRequestUriHeader = newValue)
  def withTransparentHeadRequests(newValue: Boolean): ServerSettings = self.copy(transparentHeadRequests = newValue)
  def withVerboseErrorMessages(newValue: Boolean): ServerSettings = self.copy(verboseErrorMessages = newValue)
  def withResponseHeaderSizeHint(newValue: Int): ServerSettings = self.copy(responseHeaderSizeHint = newValue)
  def withBacklog(newValue: Int): ServerSettings = self.copy(backlog = newValue)
  def withSocketOptions(newValue: java.lang.Iterable[SocketOption]): ServerSettings = self.copy(socketOptions = newValue.asScala.toList)
  def withDefaultHostHeader(newValue: Host): ServerSettings = self.copy(defaultHostHeader = newValue.asScala)
  def withParserSettings(newValue: ParserSettings): ServerSettings = self.copy(parserSettings = newValue.asScala)
  def withWebsocketRandomFactory(newValue: java.util.function.Supplier[Random]): ServerSettings = self.copy(websocketSettings = websocketSettings.withRandomFactoryFactory(new Supplier[Random] {
    override def get(): Random = newValue.get()
  }))
  def withWebsocketSettings(newValue: WebSocketSettings): ServerSettings = self.copy(websocketSettings = newValue.asScala)
  def withLogUnencryptedNetworkBytes(newValue: Optional[Int]): ServerSettings = self.copy(logUnencryptedNetworkBytes = OptionConverters.toScala(newValue))
  def withHttp2Settings(newValue: Http2ServerSettings): ServerSettings = self.copy(http2Settings = newValue.asScala)
  def withDefaultHttpPort(newValue: Int): ServerSettings = self.copy(defaultHttpPort = newValue)
  def withDefaultHttpsPort(newValue: Int): ServerSettings = self.copy(defaultHttpPort = newValue)
  def withTerminationDeadlineExceededResponse(response: akka.http.javadsl.model.HttpResponse): ServerSettings =
    self.copy(terminationDeadlineExceededResponse = response.asScala)
  def withStreamCancellationDelay(newValue: FiniteDuration): ServerSettings
}

object ServerSettings extends SettingsCompanion[ServerSettings] {
  trait Timeouts {
    def idleTimeout: Duration
    def requestTimeout: Duration
    def bindTimeout: FiniteDuration
    def lingerTimeout: Duration

    // ---
    def withIdleTimeout(newValue: Duration): Timeouts = self.copy(idleTimeout = newValue)
    def withRequestTimeout(newValue: Duration): Timeouts = self.copy(requestTimeout = newValue)
    def withBindTimeout(newValue: FiniteDuration): Timeouts = self.copy(bindTimeout = newValue)
    def withLingerTimeout(newValue: Duration): Timeouts = self.copy(lingerTimeout = newValue)

    /** INTERNAL API */
    @InternalApi
    protected def self = this.asInstanceOf[ServerSettingsImpl.Timeouts]
  }

  override def create(config: Config): ServerSettings = ServerSettingsImpl(config)
  override def create(configOverrides: String): ServerSettings = ServerSettingsImpl(configOverrides)
  override def create(system: ActorSystem): ServerSettings = create(system.settings.config)
}
