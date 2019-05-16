/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.settings

import akka.annotation.DoNotInherit
import akka.http.impl.settings.RoutingSettingsImpl
import com.typesafe.config.Config

/**
 * Public API but not intended for subclassing
 */
@DoNotInherit
abstract class RoutingSettings private[akka] () extends akka.http.javadsl.settings.RoutingSettings { self: RoutingSettingsImpl =>
  def verboseErrorMessages: Boolean
  def fileGetConditional: Boolean
  def renderVanityFooter: Boolean
  def rangeCountLimit: Int
  def rangeCoalescingThreshold: Long
  def decodeMaxBytesPerChunk: Int
  def decodeMaxSize: Long
  @deprecated("binary compatibility method. Use `akka.stream.materializer.blocking-io-dispatcher` to configure the dispatcher", since = "10.1.6")
  def fileIODispatcher: String

  /* Java APIs */
  def getVerboseErrorMessages: Boolean = verboseErrorMessages
  def getFileGetConditional: Boolean = fileGetConditional
  def getRenderVanityFooter: Boolean = renderVanityFooter
  def getRangeCountLimit: Int = rangeCountLimit
  def getRangeCoalescingThreshold: Long = rangeCoalescingThreshold
  def getDecodeMaxBytesPerChunk: Int = decodeMaxBytesPerChunk
  def getDecodeMaxSize: Long = decodeMaxSize
  @deprecated("binary compatibility method. Use `akka.stream.materializer.blocking-io-dispatcher` to configure the dispatcher", since = "10.1.6")
  @Deprecated
  def getFileIODispatcher: String = fileIODispatcher

  override def withVerboseErrorMessages(verboseErrorMessages: Boolean): RoutingSettings = self.copy(verboseErrorMessages = verboseErrorMessages)
  override def withFileGetConditional(fileGetConditional: Boolean): RoutingSettings = self.copy(fileGetConditional = fileGetConditional)
  override def withRenderVanityFooter(renderVanityFooter: Boolean): RoutingSettings = self.copy(renderVanityFooter = renderVanityFooter)
  override def withRangeCountLimit(rangeCountLimit: Int): RoutingSettings = self.copy(rangeCountLimit = rangeCountLimit)
  override def withRangeCoalescingThreshold(rangeCoalescingThreshold: Long): RoutingSettings = self.copy(rangeCoalescingThreshold = rangeCoalescingThreshold)
  override def withDecodeMaxBytesPerChunk(decodeMaxBytesPerChunk: Int): RoutingSettings = self.copy(decodeMaxBytesPerChunk = decodeMaxBytesPerChunk)
  override def withDecodeMaxSize(decodeMaxSize: Long): RoutingSettings = self.copy(decodeMaxSize = decodeMaxSize)
  @deprecated("binary compatibility method. Use `akka.stream.materializer.blocking-io-dispatcher` to configure the dispatcher", since = "10.1.6")
  @Deprecated
  override def withFileIODispatcher(fileIODispatcher: String): RoutingSettings = self
}

object RoutingSettings extends SettingsCompanion[RoutingSettings] {
  override def apply(config: Config): RoutingSettings = RoutingSettingsImpl(config)
  override def apply(configOverrides: String): RoutingSettings = RoutingSettingsImpl(configOverrides)
}
