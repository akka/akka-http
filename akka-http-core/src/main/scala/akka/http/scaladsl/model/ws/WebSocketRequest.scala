/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.model.ws

import scala.language.implicitConversions

import scala.collection.immutable

import akka.http.scaladsl.model.{ HttpHeader, Uri }

/**
 * Represents a WebSocket request.
 * @param uri The target URI to connect to.
 * @param extraHeaders Extra headers to add to the WebSocket request.
 * @param subprotocol WebSocket subprotocols (comma separated) if required.
 */
final case class WebSocketRequest(
  uri:          Uri,
  extraHeaders: immutable.Seq[HttpHeader] = Nil,
  subprotocol:  Option[String]            = None)
object WebSocketRequest {
  implicit def fromTargetUri(uri: Uri): WebSocketRequest = WebSocketRequest(uri)
  implicit def fromTargetUriString(uriString: String): WebSocketRequest = WebSocketRequest(uriString)

  def apply(
    uri:          Uri,
    extraHeaders: immutable.Seq[HttpHeader],
    subprotocols: immutable.Seq[String]): WebSocketRequest =
    WebSocketRequest(
      uri,
      extraHeaders,
      if (subprotocols.nonEmpty) Some(subprotocols.mkString(",")) else None
    )
}
