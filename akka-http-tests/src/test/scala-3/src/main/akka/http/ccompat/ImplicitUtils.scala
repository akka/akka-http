/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.ccompat

import scala.collection.immutable.StringOps

object ImplicitUtils {
  // Scala 3 resolves implicit conversions differently than Scala 2,
  // in some instances overriding StringOps operations, like *.
  implicit class Scala3StringOpsFix(string: String) {
    def *(amount: Int): String = StringOps(string) * amount
  }
}
