/*
 * Copyright (C) 2009-2025 Lightbend Inc. <https://akka.io>
 */

package akka.http.scaladsl.model.headers

import akka.http.impl.util.{ Rendering, ValueRenderable }
import akka.http.javadsl.{ model => jm }

sealed abstract class RangeUnit extends jm.headers.RangeUnit with ValueRenderable {
  def name: String
}

object RangeUnits {
  case object Bytes extends RangeUnit {
    def name = "Bytes"

    def render[R <: Rendering](r: R): r.type = r ~~ "bytes"
  }

  final case class Other(name: String) extends RangeUnit {
    def render[R <: Rendering](r: R): r.type = r ~~ name
  }
}
