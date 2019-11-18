/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.model.headers

import akka.http.impl.util.{ Rendering, ValueRenderable }

final case class ViaIntermediary(protocolName: Option[String], protocolVersion: String, receivedBy: String) extends ValueRenderable {
  def render[R <: Rendering](r: R): r.type = {
    protocolName.foreach(name => r ~~ s"$name/")
    r ~~ protocolVersion ~~ ' ' ~~ receivedBy
    r
  }
}
