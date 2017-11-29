/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.ws

import akka.http.scaladsl.model.headers.InternalCustomHeader
import akka.http.scaladsl.model.ws.Message
import akka.stream.{ FlowShape, Graph }

private[http] final case class UpgradeToWebSocketResponseHeader(handler: Either[Graph[FlowShape[FrameEvent, FrameEvent], Any], Graph[FlowShape[Message, Message], Any]])
  extends InternalCustomHeader("UpgradeToWebSocketResponseHeader")
