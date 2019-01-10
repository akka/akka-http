/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http

import akka.http.impl.util.JavaMapping

/**
 * INTERNAL API
 */
package object ccompat {

  /**
   * On scala 2.12, we often provide the same method both in 'immutable.Seq' and 'varargs' variation.
   *
   * On scala 2.13, this is no longer necessary, since varargs are treated as immutable. Therefore we need
   * to use a more generic type so we can disambiguate the varargs and non-varargs methods for 2.13:
   */
  type VASeq[+A] = scala.collection.immutable.Iterable[A]
  implicit class ConvertableVASeq[A](val seq: VASeq[A]) extends AnyVal {
    // This will almost always be 'asSeq', since the only reason 'VASeq' is 'Iterable'
    // and not 'Seq' is to avoid ambiguity. Still seems strange to call this 'asSeq'
    // when the compiler cannot guarantee it is not 'toSeq'...
    def xSeq: scala.collection.immutable.Seq[A] = seq match {
      case s: Seq[A] ⇒ s
      case _         ⇒ seq.toSeq
    }
    def length = seq.size
  }

  type Builder[-A, +To] = scala.collection.mutable.Builder[A, To]

  import akka.http.scaladsl.model.Uri.Query
  trait QuerySeqOptimized extends scala.collection.immutable.LinearSeq[(String, String)] with scala.collection.StrictOptimizedLinearSeqOps[(String, String), scala.collection.immutable.LinearSeq, Query] {

    override protected def fromSpecific(coll: IterableOnce[(String, String)]): Query =
      Query(coll.toSeq: _*)

    def newBuilder: Any = ???
  }
}
