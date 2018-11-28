package akka.http

import akka.http.impl.util.JavaMapping

package object ccompat {

  /**
   * On scala 2.12, we often provide the same method both in 'immutable.Seq' and 'varargs' variation.
   *
   * On scala 2.13, this is no longer necessary, since varargs are treated as immutable. Therefore we need
   * to use a more generic type so we can disambiguate the varargs and non-varargs methods for 2.13:
   */
  type VASeq[+A] = scala.collection.immutable.Iterable[A]
  implicit class ConvertableVASeq[A](val seq: VASeq[A]) extends AnyVal {
    def asJava[JA](implicit mapping: JavaMapping[JA, A]): java.lang.Iterable[JA] =
      scala.collection.JavaConverters.asJavaCollection(seq.map(mapping.toJava(_)))
    def xSeq: scala.collection.immutable.Seq[A] = seq match {
      case s: Seq[A] ⇒ s
      case _         ⇒ seq.toSeq
    }
    def length = seq.size
  }

  type Builder[-A, +To] = scala.collection.mutable.Builder[A, To]

  trait LinearSeqOptimized[+A, +C <: scala.collection.LinearSeq[A] with scala.collection.StrictOptimizedLinearSeqOps[A, scala.collection.immutable.LinearSeq, C]]
    extends scala.collection.StrictOptimizedLinearSeqOps[A, scala.collection.immutable.LinearSeq, C] {
    def newBuilder: Any = ???
  }
}
