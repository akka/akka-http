/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.settings

import java.time.Duration

import akka.http.scaladsl

import scala.jdk.DurationConverters.JavaDurationOps
import scala.jdk.DurationConverters.ScalaDurationOps

trait Http2ClientSettings { self: scaladsl.settings.Http2ClientSettings.Http2ClientSettingsImpl =>
  def requestEntityChunkSize: Int
  def withRequestEntityChunkSize(newValue: Int): Http2ClientSettings = self.copy(requestEntityChunkSize = newValue)

  def incomingConnectionLevelBufferSize: Int
  def withIncomingConnectionLevelBufferSize(newValue: Int): Http2ClientSettings = self.copy(incomingConnectionLevelBufferSize = newValue)

  def incomingStreamLevelBufferSize: Int
  def withIncomingStreamLevelBufferSize(newValue: Int): Http2ClientSettings = copy(incomingStreamLevelBufferSize = newValue)

  def maxConcurrentStreams: Int
  def withMaxConcurrentStreams(newValue: Int): Http2ClientSettings = copy(maxConcurrentStreams = newValue)

  def outgoingControlFrameBufferSize: Int
  def withOutgoingControlFrameBufferSize(newValue: Int): Http2ClientSettings = copy(outgoingControlFrameBufferSize = newValue)

  def logFrames: Boolean
  def withLogFrames(shouldLog: Boolean): Http2ClientSettings = copy(logFrames = shouldLog)

  def getPingInterval: Duration = pingInterval.toJava
  def withPingInterval(interval: Duration): Http2ClientSettings = copy(pingInterval = interval.toScala)

  def getPingTimeout: Duration = pingTimeout.toJava
  def withPingTimeout(timeout: Duration): Http2ClientSettings = copy(pingTimeout = timeout.toScala)

}
