/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.settings

import akka.annotation.ApiMayChange
import akka.annotation.DoNotInherit
import akka.annotation.InternalApi
import akka.http.impl.util._
import akka.http.javadsl
import com.typesafe.config.Config

import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration

/**
 * INTERNAL API
 *
 * Settings which are common for server and client side.
 */
@InternalApi
@DoNotInherit
private[http] trait Http2CommonSettings {
  def requestEntityChunkSize: Int
  def incomingConnectionLevelBufferSize: Int
  def incomingStreamLevelBufferSize: Int

  def logFrames: Boolean
  def maxConcurrentStreams: Int
  def outgoingControlFrameBufferSize: Int

  def pingInterval: FiniteDuration
  def pingTimeout: FiniteDuration
}

/**
 * INTERNAL API
 */
@InternalApi
private[http] object Http2CommonSettings {
  def validate(settings: Http2CommonSettings): Unit = {
    import settings._
    if (pingInterval > Duration.Zero && pingTimeout > Duration.Zero) {
      require(
        pingTimeout <= pingInterval && pingInterval.toMillis % pingTimeout.toMillis == 0,
        s"ping-timeout must be less than and evenly divisible by the ping-interval ($pingInterval)")
    }
  }
}

/**
 * Placeholder for any kind of internal settings that might be interesting for HTTP/2 (like custom strategies)
 */
@InternalApi
@DoNotInherit
private[http] trait Http2InternalServerSettings

@ApiMayChange
@DoNotInherit
trait Http2ServerSettings extends javadsl.settings.Http2ServerSettings with Http2CommonSettings { self: Http2ServerSettings.Http2ServerSettingsImpl =>
  def requestEntityChunkSize: Int
  def withRequestEntityChunkSize(newValue: Int): Http2ServerSettings = copy(requestEntityChunkSize = newValue)

  def incomingConnectionLevelBufferSize: Int
  def withIncomingConnectionLevelBufferSize(newValue: Int): Http2ServerSettings = copy(incomingConnectionLevelBufferSize = newValue)

  def incomingStreamLevelBufferSize: Int
  def withIncomingStreamLevelBufferSize(newValue: Int): Http2ServerSettings = copy(incomingStreamLevelBufferSize = newValue)

  def maxConcurrentStreams: Int
  override def withMaxConcurrentStreams(newValue: Int): Http2ServerSettings = copy(maxConcurrentStreams = newValue)

  def outgoingControlFrameBufferSize: Int
  override def withOutgoingControlFrameBufferSize(newValue: Int): Http2ServerSettings = copy(outgoingControlFrameBufferSize = newValue)

  def logFrames: Boolean
  override def withLogFrames(shouldLog: Boolean): Http2ServerSettings = copy(logFrames = shouldLog)

  def pingInterval: FiniteDuration
  def withPingInterval(time: FiniteDuration): Http2ServerSettings = copy(pingInterval = time)

  def pingTimeout: FiniteDuration
  def withPingTimeout(timeout: FiniteDuration): Http2ServerSettings = copy(pingTimeout = timeout)

  @InternalApi
  private[http] def internalSettings: Option[Http2InternalServerSettings]
  @InternalApi
  private[http] def withInternalSettings(newValue: Http2InternalServerSettings): Http2ServerSettings =
    copy(internalSettings = Some(newValue))
}

@ApiMayChange
object Http2ServerSettings extends SettingsCompanion[Http2ServerSettings] {
  def apply(config: Config): Http2ServerSettings = Http2ServerSettingsImpl(config)
  def apply(configOverrides: String): Http2ServerSettings = Http2ServerSettingsImpl(configOverrides)

  private[http] case class Http2ServerSettingsImpl(
    maxConcurrentStreams:              Int,
    requestEntityChunkSize:            Int,
    incomingConnectionLevelBufferSize: Int,
    incomingStreamLevelBufferSize:     Int,
    outgoingControlFrameBufferSize:    Int,
    logFrames:                         Boolean,
    pingInterval:                      FiniteDuration,
    pingTimeout:                       FiniteDuration,
    internalSettings:                  Option[Http2InternalServerSettings])
    extends Http2ServerSettings {
    require(maxConcurrentStreams >= 0, "max-concurrent-streams must be >= 0")
    require(requestEntityChunkSize > 0, "request-entity-chunk-size must be > 0")
    require(incomingConnectionLevelBufferSize > 0, "incoming-connection-level-buffer-size must be > 0")
    require(incomingStreamLevelBufferSize > 0, "incoming-stream-level-buffer-size must be > 0")
    require(outgoingControlFrameBufferSize > 0, "outgoing-control-frame-buffer-size must be > 0")
    Http2CommonSettings.validate(this)
  }

  private[http] object Http2ServerSettingsImpl extends akka.http.impl.util.SettingsCompanionImpl[Http2ServerSettingsImpl]("akka.http.server.http2") {
    def fromSubConfig(root: Config, c: Config): Http2ServerSettingsImpl = Http2ServerSettingsImpl(
      maxConcurrentStreams = c.getInt("max-concurrent-streams"),
      requestEntityChunkSize = c.getIntBytes("request-entity-chunk-size"),
      incomingConnectionLevelBufferSize = c.getIntBytes("incoming-connection-level-buffer-size"),
      incomingStreamLevelBufferSize = c.getIntBytes("incoming-stream-level-buffer-size"),
      outgoingControlFrameBufferSize = c.getIntBytes("outgoing-control-frame-buffer-size"),
      logFrames = c.getBoolean("log-frames"),
      pingInterval = c.getFiniteDuration("ping-interval"),
      pingTimeout = c.getFiniteDuration("ping-timeout"),
      None // no possibility to configure internal settings with config
    )
  }
}

/**
 * Placeholder for any kind of internal settings that might be interesting for HTTP/2 (like custom strategies)
 */
@InternalApi
@DoNotInherit
private[http] trait Http2InternalClientSettings

@ApiMayChange
@DoNotInherit
trait Http2ClientSettings extends javadsl.settings.Http2ClientSettings with Http2CommonSettings { self: Http2ClientSettings.Http2ClientSettingsImpl =>
  def requestEntityChunkSize: Int
  override def withRequestEntityChunkSize(newValue: Int): Http2ClientSettings = copy(requestEntityChunkSize = newValue)

  def incomingConnectionLevelBufferSize: Int
  override def withIncomingConnectionLevelBufferSize(newValue: Int): Http2ClientSettings = copy(incomingConnectionLevelBufferSize = newValue)

  def incomingStreamLevelBufferSize: Int
  override def withIncomingStreamLevelBufferSize(newValue: Int): Http2ClientSettings = copy(incomingStreamLevelBufferSize = newValue)

  def maxConcurrentStreams: Int
  override def withMaxConcurrentStreams(newValue: Int): Http2ClientSettings = copy(maxConcurrentStreams = newValue)

  def outgoingControlFrameBufferSize: Int
  override def withOutgoingControlFrameBufferSize(newValue: Int): Http2ClientSettings = copy(outgoingControlFrameBufferSize = newValue)

  def logFrames: Boolean
  override def withLogFrames(shouldLog: Boolean): Http2ClientSettings = copy(logFrames = shouldLog)

  def pingInterval: FiniteDuration
  def withPingInterval(time: FiniteDuration): Http2ClientSettings = copy(pingInterval = time)

  def pingTimeout: FiniteDuration
  def withPingTimeout(timeout: FiniteDuration): Http2ClientSettings = copy(pingTimeout = timeout)

  def maxPersistentAttempts: Int
  override def withMaxPersistentAttempts(max: Int): Http2ClientSettings = copy(maxPersistentAttempts = max)

  @InternalApi
  private[http] def internalSettings: Option[Http2InternalClientSettings]
  @InternalApi
  private[http] def withInternalSettings(newValue: Http2InternalClientSettings): Http2ClientSettings =
    copy(internalSettings = Some(newValue))
}

@ApiMayChange
object Http2ClientSettings extends SettingsCompanion[Http2ClientSettings] {
  def apply(config: Config): Http2ClientSettings = Http2ClientSettingsImpl(config)
  def apply(configOverrides: String): Http2ClientSettings = Http2ClientSettingsImpl(configOverrides)

  private[http] case class Http2ClientSettingsImpl(
    maxConcurrentStreams:              Int,
    requestEntityChunkSize:            Int,
    incomingConnectionLevelBufferSize: Int,
    incomingStreamLevelBufferSize:     Int,
    outgoingControlFrameBufferSize:    Int,
    logFrames:                         Boolean,
    pingInterval:                      FiniteDuration,
    pingTimeout:                       FiniteDuration,
    maxPersistentAttempts:             Int,
    internalSettings:                  Option[Http2InternalClientSettings])
    extends Http2ClientSettings with javadsl.settings.Http2ClientSettings {
    require(maxConcurrentStreams >= 0, "max-concurrent-streams must be >= 0")
    require(requestEntityChunkSize > 0, "request-entity-chunk-size must be > 0")
    require(incomingConnectionLevelBufferSize > 0, "incoming-connection-level-buffer-size must be > 0")
    require(incomingStreamLevelBufferSize > 0, "incoming-stream-level-buffer-size must be > 0")
    require(outgoingControlFrameBufferSize > 0, "outgoing-control-frame-buffer-size must be > 0")
    require(maxPersistentAttempts >= 0, "max-persistent-attempts must be >= 0")
    Http2CommonSettings.validate(this)
  }

  private[http] object Http2ClientSettingsImpl extends akka.http.impl.util.SettingsCompanionImpl[Http2ClientSettingsImpl]("akka.http.client.http2") {
    def fromSubConfig(root: Config, c: Config): Http2ClientSettingsImpl = Http2ClientSettingsImpl(
      maxConcurrentStreams = c.getInt("max-concurrent-streams"),
      requestEntityChunkSize = c.getIntBytes("request-entity-chunk-size"),
      incomingConnectionLevelBufferSize = c.getIntBytes("incoming-connection-level-buffer-size"),
      incomingStreamLevelBufferSize = c.getIntBytes("incoming-stream-level-buffer-size"),
      outgoingControlFrameBufferSize = c.getIntBytes("outgoing-control-frame-buffer-size"),
      logFrames = c.getBoolean("log-frames"),
      pingInterval = c.getFiniteDuration("ping-interval"),
      pingTimeout = c.getFiniteDuration("ping-timeout"),
      maxPersistentAttempts = c.getInt("max-persistent-attempts"),
      internalSettings = None // no possibility to configure internal settings with config
    )
  }
}
