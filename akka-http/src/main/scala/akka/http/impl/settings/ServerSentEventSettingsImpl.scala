/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.settings

import akka.annotation.InternalApi
import akka.http.impl.util.SettingsCompanionImpl
import com.typesafe.config.Config

@InternalApi
private[http] final case class ServerSentEventSettingsImpl(
  maxEventSize: Int,
  maxLineSize:  Int
) extends akka.http.scaladsl.settings.ServerSentEventSettings {
  require(maxLineSize > 0, "max-line-size must be greater than 0")
  require(maxEventSize > maxLineSize, "max-event-size must be greater than max-line-size")

  override def productPrefix: String = "ServerSentEventSettings"

}

object ServerSentEventSettingsImpl extends SettingsCompanionImpl[ServerSentEventSettingsImpl]("akka.http.sse") {
  def fromSubConfig(root: Config, c: Config) = ServerSentEventSettingsImpl(
    c.getInt("max-event-size"),
    c.getInt("max-line-size")
  )
}
