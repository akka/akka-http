/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http

/**
 * INTERNAL API
 */
package object ccompat {

  type Builder[-A, +To] = scala.collection.mutable.Builder[A, To]
}

/**
 * INTERNAL API
 */
package ccompat {
  import akka.http.scaladsl.model.Uri.Query
  trait QuerySeqOptimized extends scala.collection.immutable.LinearSeq[(String, String)] with scala.collection.StrictOptimizedLinearSeqOps[(String, String), scala.collection.immutable.LinearSeq, Query] {
    override protected def fromSpecific(coll: IterableOnce[(String, String)]): Query =
      Query(coll.iterator.to(Seq): _*)

    def newBuilder: Any = akka.http.scaladsl.model.Uri.Query.newBuilder
  }
}
