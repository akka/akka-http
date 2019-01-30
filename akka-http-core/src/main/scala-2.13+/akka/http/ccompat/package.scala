/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http

import akka.http.impl.util.JavaMapping

/**
 * INTERNAL API
 */
package object ccompat {

  type Builder[-A, +To] = scala.collection.mutable.Builder[A, To]

  import akka.http.scaladsl.model.Uri.Query
  trait QuerySeqOptimized extends scala.collection.immutable.LinearSeq[(String, String)] with scala.collection.StrictOptimizedLinearSeqOps[(String, String), scala.collection.immutable.LinearSeq, Query] {

    override protected def fromSpecific(coll: IterableOnce[(String, String)]): Query =
      Query(coll.toSeq: _*)

    def newBuilder: Any = ???
  }
}
