/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.ccompat

import scala.collection.immutable

/**
 * INTERNAL API
 */
package object imm {
  implicit class SortedSetOps[A](val real: immutable.SortedSet[A]) extends AnyVal {
    def unsorted: immutable.Set[A] = real
  }

  implicit class StreamOps[A](val underlying: immutable.Stream[A]) extends AnyVal {
    // renamed in 2.13
    def lazyAppendedAll[B >: A](rest: ⇒ TraversableOnce[B]): Stream[B] = underlying.append(rest)
  }
}
