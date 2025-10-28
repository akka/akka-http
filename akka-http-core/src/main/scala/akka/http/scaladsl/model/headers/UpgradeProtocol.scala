/*
 * Copyright (C) 2009-2025 Lightbend Inc. <https://akka.io>
 */

package akka.http.scaladsl.model.headers

import akka.http.impl.util.{ Rendering, ValueRenderable }

final case class UpgradeProtocol(name: String, version: Option[String] = None) extends ValueRenderable {
  def render[R <: Rendering](r: R): r.type = {
    r ~~ name
    version.foreach(v => r ~~ '/' ~~ v)
    r
  }
}
