/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.settings

import java.util.Random

import akka.http.scaladsl.settings.{ SettingsCompanion => _, _ }
import com.typesafe.config.Config

import scala.language.implicitConversions
import scala.collection.immutable
import scala.concurrent.duration._
import akka.http.javadsl.{ settings => js }
import akka.ConfigurationException
import akka.annotation.InternalApi
import akka.io.Inet.SocketOption
import akka.http.impl.util._
import akka.http.scaladsl.model.{ HttpHeader, HttpResponse, StatusCodes }
import akka.http.scaladsl.model.headers.{ Host, Server }
import akka.http.scaladsl.settings.ServerSettings.LogUnencryptedNetworkBytes

/** INTERNAL API */
@InternalApi
private[akka] final case class ServerSettingsImpl(
  serverHeader:                        Option[Server],
  previewServerSettings:               PreviewServerSettings,
  timeouts:                            ServerSettings.Timeouts,
  maxConnections:                      Int,
  pipeliningLimit:                     Int,
  remoteAddressHeader:                 Boolean,
  rawRequestUriHeader:                 Boolean,
  transparentHeadRequests:             Boolean,
  verboseErrorMessages:                Boolean,
  responseHeaderSizeHint:              Int,
  backlog:                             Int,
  logUnencryptedNetworkBytes:          Option[Int],
  socketOptions:                       immutable.Seq[SocketOption],
  defaultHostHeader:                   Host,
  websocketSettings:                   WebSocketSettings,
  parserSettings:                      ParserSettings,
  http2Settings:                       Http2ServerSettings,
  defaultHttpPort:                     Int,
  defaultHttpsPort:                    Int,
  terminationDeadlineExceededResponse: HttpResponse,
  streamCancellationDelay:             FiniteDuration) extends ServerSettings {

  require(0 < maxConnections, "max-connections must be > 0")
  require(0 < pipeliningLimit && pipeliningLimit <= 1024, "pipelining-limit must be > 0 and <= 1024")
  require(0 < responseHeaderSizeHint, "response-size-hint must be > 0")
  require(0 < backlog, "backlog must be > 0")

  override def websocketRandomFactory: () => Random = websocketSettings.randomFactory

  override def productPrefix = "ServerSettings"

}

/** INTERNAL API */
@InternalApi
private[http] object ServerSettingsImpl extends SettingsCompanionImpl[ServerSettingsImpl]("akka.http.server") {
  implicit def timeoutsShortcut(s: js.ServerSettings): js.ServerSettings.Timeouts = s.getTimeouts

  final case class Timeouts(
    idleTimeout:    Duration,
    requestTimeout: Duration,
    bindTimeout:    FiniteDuration,
    lingerTimeout:  Duration) extends ServerSettings.Timeouts {
    require(idleTimeout > Duration.Zero, "idleTimeout must be infinite or > 0")
    require(requestTimeout > Duration.Zero, "requestTimeout must be infinite or > 0")
    require(bindTimeout > Duration.Zero, "bindTimeout must be > 0")
    require(lingerTimeout > Duration.Zero, "lingerTimeout must be infinite or > 0")
  }

  def fromSubConfig(root: Config, c: Config) = new ServerSettingsImpl(
    c.getString("server-header").toOption.map(Server(_)),
    PreviewServerSettingsImpl.fromSubConfig(root, c.getConfig("preview")),
    Timeouts(
      c.getPotentiallyInfiniteDuration("idle-timeout"),
      c.getPotentiallyInfiniteDuration("request-timeout"),
      c.getFiniteDuration("bind-timeout"),
      c.getPotentiallyInfiniteDuration("linger-timeout")),
    c.getInt("max-connections"),
    c.getInt("pipelining-limit"),
    c.getBoolean("remote-address-header"),
    c.getBoolean("raw-request-uri-header"),
    c.getBoolean("transparent-head-requests"),
    c.getBoolean("verbose-error-messages"),
    c.getIntBytes("response-header-size-hint"),
    c.getInt("backlog"),
    LogUnencryptedNetworkBytes(c.getString("log-unencrypted-network-bytes")),
    SocketOptionSettings.fromSubConfig(root, c.getConfig("socket-options")),
    defaultHostHeader =
      HttpHeader.parse("Host", c.getString("default-host-header"), ParserSettings(root)) match {
        case HttpHeader.ParsingResult.Ok(x: Host, Nil) => x
        case result =>
          val info = result.errors.head.withSummary("Configured `default-host-header` is illegal")
          throw new ConfigurationException(info.formatPretty)
      },
    WebSocketSettingsImpl.server(c.getConfig("websocket")),
    ParserSettingsImpl.fromSubConfig(root, c.getConfig("parsing")),
    Http2ServerSettings.Http2ServerSettingsImpl.fromSubConfig(root, c.getConfig("http2")),
    c.getInt("default-http-port"),
    c.getInt("default-https-port"),
    terminationDeadlineExceededResponseFrom(c),
    c.getFiniteDuration("stream-cancellation-delay")
  )

  private def terminationDeadlineExceededResponseFrom(c: Config): HttpResponse = {
    val status = c getInt "termination-deadline-exceeded-response.status"
    HttpResponse(
      status = StatusCodes.getForKey(status)
        .getOrElse(throw new IllegalArgumentException(s"Illegal status code set for `termination-deadline-exceeded-response.status`, was: [$status]"))
    )
  }

}
