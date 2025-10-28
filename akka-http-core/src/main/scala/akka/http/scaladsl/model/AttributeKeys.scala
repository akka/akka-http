/*
 * Copyright (C) 2019-2025 Lightbend Inc. <https://akka.io>
 */

package akka.http.scaladsl.model

import akka.http.scaladsl.model.ws.WebSocketUpgrade

object AttributeKeys {
  val remoteAddress = AttributeKey[RemoteAddress]("remote-address")
  val webSocketUpgrade = AttributeKey[WebSocketUpgrade](name = "upgrade-to-websocket")
  val sslSession = AttributeKey[SslSessionInfo](name = "ssl-session")
  val trailer = AttributeKey[Trailer](name = "trailer")
}

