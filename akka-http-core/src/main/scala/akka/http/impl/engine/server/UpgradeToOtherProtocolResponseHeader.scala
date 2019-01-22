/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.server

import akka.http.scaladsl.model.headers.CustomHeader
import akka.stream.scaladsl.Flow
import akka.util.ByteString

private[http] final case class UpgradeToOtherProtocolResponseHeader(handler: Flow[ByteString, ByteString, Any])
  extends InternalCustomHeader("UpgradeToOtherProtocolResponseHeader")

private[http] abstract class InternalCustomHeader(val name: String) extends CustomHeader {
  final def renderInRequests = false
  final def renderInResponses = false
  def value: String = ""
}
