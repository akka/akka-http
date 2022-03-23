/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.server.util

// in 2.13 (T*) => U is not a valid type any more, this works on 2.12+ as a drop in replacement
trait VarArgsFunction1[-T, +U] {
  def apply(alternatives: T*): U
}
