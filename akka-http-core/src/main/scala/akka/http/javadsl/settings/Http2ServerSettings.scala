/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.settings

import java.time.Duration

import akka.annotation.DoNotInherit
import akka.http.scaladsl
import com.typesafe.config.Config

import scala.jdk.DurationConverters.JavaDurationOps
import scala.jdk.DurationConverters.ScalaDurationOps

@DoNotInherit
trait Http2ServerSettings { self: scaladsl.settings.Http2ServerSettings =>
  def getRequestEntityChunkSize: Int = requestEntityChunkSize
  def withRequestEntityChunkSize(newRequestEntityChunkSize: Int): Http2ServerSettings

  def getIncomingConnectionLevelBufferSize: Int = incomingConnectionLevelBufferSize
  def withIncomingConnectionLevelBufferSize(newIncomingConnectionLevelBufferSize: Int): Http2ServerSettings

  def getIncomingStreamLevelBufferSize: Int = incomingStreamLevelBufferSize
  def withIncomingStreamLevelBufferSize(newIncomingStreamLevelBufferSize: Int): Http2ServerSettings

  def getMaxConcurrentStreams: Int = maxConcurrentStreams
  def withMaxConcurrentStreams(newValue: Int): Http2ServerSettings

  def getOutgoingControlFrameBufferSize: Int = outgoingControlFrameBufferSize
  def withOutgoingControlFrameBufferSize(newValue: Int): Http2ServerSettings

  def logFrames: Boolean
  def withLogFrames(shouldLog: Boolean): Http2ServerSettings

  def getPingInterval: Duration = pingInterval.toJava
  def withPingInterval(interval: Duration): Http2ServerSettings = withPingInterval(interval.toScala)

  def getPingTimeout: Duration = pingTimeout.toJava
  def withPingTimeout(timeout: Duration): Http2ServerSettings = withPingTimeout(timeout.toScala)
}
object Http2ServerSettings extends SettingsCompanion[Http2ServerSettings] {
  def create(config: Config): Http2ServerSettings = scaladsl.settings.Http2ServerSettings(config)
  def create(configOverrides: String): Http2ServerSettings = scaladsl.settings.Http2ServerSettings(configOverrides)
}
