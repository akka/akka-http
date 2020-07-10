/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.model

import akka.http.scaladsl.model.ws.WebSocketUpgrade

object AttributeKeys {
  val remoteAddress = AttributeKey[RemoteAddress]("remote-address")
  val webSocketUpgrade = AttributeKey[WebSocketUpgrade](name = "upgrade-to-websocket")
}

