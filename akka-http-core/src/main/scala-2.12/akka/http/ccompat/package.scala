package akka.http

import scala.collection.mutable

package object ccompat {

  /**
   * On scala 2.12, we often provide the same method both in 'immutable.Seq' and 'varargs' variation.
   *
   * On scala 2.13, this is no longer necessary, since varargs are treated as immutable. Therefore we need
   * to use a specific name so we can disambiguate the 2 methods for 2.13:
   */
  type VASeq[+A] = scala.collection.immutable.Seq[A]
  implicit class ConvertableVASeq[A](val seq: VASeq[A]) extends AnyVal {
    def xSeq: scala.collection.immutable.Seq[A] = seq
  }

  trait Builder[-Elem, +To] extends mutable.Builder[Elem, To] { self ⇒
    // This became final in 2.13 so cannot be overridden there anymore
    final override def +=(elem: Elem): this.type = addOne(elem)
    def addOne(elem: Elem): this.type = self.+=(elem)
  }

  type LinearSeqOptimized[+A, +Repr <: LinearSeqOptimized[A, Repr]] = scala.collection.LinearSeqOptimized[A, Repr]

}
