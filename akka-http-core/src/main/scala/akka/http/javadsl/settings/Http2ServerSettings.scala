/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.settings

import akka.annotation.DoNotInherit
import akka.http.scaladsl
import com.typesafe.config.Config

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

  def logFrames: Boolean
  def withLogFrames(shouldLog: Boolean): Http2ServerSettings
}
object Http2ServerSettings extends SettingsCompanion[Http2ServerSettings] {
  def create(config: Config): Http2ServerSettings = scaladsl.settings.Http2ServerSettings(config)
  def create(configOverrides: String): Http2ServerSettings = scaladsl.settings.Http2ServerSettings(configOverrides)
}
