/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.settings

import akka.annotation.InternalApi
import akka.http.impl.util._
import com.typesafe.config.Config

/** INTERNAL API */
@InternalApi
private[http] final case class RoutingSettingsImpl(
  verboseErrorMessages:     Boolean,
  fileGetConditional:       Boolean,
  renderVanityFooter:       Boolean,
  rangeCountLimit:          Int,
  rangeCoalescingThreshold: Long,
  decodeMaxBytesPerChunk:   Int,
  decodeMaxSize:            Long) extends akka.http.scaladsl.settings.RoutingSettings {

  @deprecated("binary compatibility method. Use `akka.stream.materializer.blocking-io-dispatcher` to configure the dispatcher", since = "10.1.6")
  override def fileIODispatcher: String = ""

  override def productPrefix = "RoutingSettings"
}

object RoutingSettingsImpl extends SettingsCompanionImpl[RoutingSettingsImpl]("akka.http.routing") {
  def fromSubConfig(root: Config, c: Config) = new RoutingSettingsImpl(
    c.getBoolean("verbose-error-messages"),
    c.getBoolean("file-get-conditional"),
    c.getBoolean("render-vanity-footer"),
    c.getInt("range-count-limit"),
    c.getBytes("range-coalescing-threshold"),
    c.getIntBytes("decode-max-bytes-per-chunk"),
    c.getPossiblyInfiniteBytes("decode-max-size")
  )
}
