/*
 * Copyright (C) 2019-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.model

import akka.http.scaladsl.model.ws.WebSocketUpgrade
import scala.collection.immutable

object AttributeKeys {
  val remoteAddress = AttributeKey[RemoteAddress]("remote-address")
  val webSocketUpgrade = AttributeKey[WebSocketUpgrade](name = "upgrade-to-websocket")
  val sslSession = AttributeKey[SslSessionInfo](name = "ssl-session")
  // Trailing headers for HTTP/2 responses
  val trailingHeaders = AttributeKey[immutable.Seq[HttpHeader]](name = "trailing-headers")
}

