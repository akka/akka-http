/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import org.scalactic.source
import org.scalatest.wordspec.AnyWordSpecLike

/** Adds `"test" inPendingUntilFixed {...}` which is equivalent to `"test" in pendingUntilFixed({...})` */
trait WithInPendingUntilFixed extends AnyWordSpecLike {
  implicit class InPendingUntilFixed(val str: String) {
    def inPendingUntilFixed(f: => Any /* Assertion */ )(implicit pos: source.Position): Unit =
      str.in(pendingUntilFixed(f))(pos)
  }
}
