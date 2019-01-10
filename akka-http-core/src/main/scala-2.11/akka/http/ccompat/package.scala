/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http

import scala.collection.generic.{ CanBuildFrom, GenericCompanion }
import scala.collection.{ GenTraversable, mutable }
import scala.{ collection ⇒ c }

/**
 * INTERNAL API
 *
 * Partly based on https://github.com/scala/scala-collection-compat/blob/master/compat/src/main/scala-2.11_2.12/scala/collection/compat/PackageShared.scala
 * but reproduced here so we don't need to add a dependency on this library. It contains much more than we need right now, and is
 * not promising binary compatibility yet at the time of writing.
 */
package object ccompat {
  import CompatImpl._

  implicit def genericCompanionToCBF[A, CC[X] <: GenTraversable[X]](
    fact: GenericCompanion[CC]): CanBuildFrom[Any, A, CC[A]] =
    simpleCBF(fact.newBuilder[A])

  // This really belongs into scala.collection but there's already a package object
  // in scala-library so we can't add to it
  type IterableOnce[+X] = c.TraversableOnce[X]
  val IterableOnce = c.TraversableOnce

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

  trait QuerySeqOptimized extends scala.collection.immutable.LinearSeq[(String, String)] with scala.collection.LinearSeqOptimized[(String, String), akka.http.scaladsl.model.Uri.Query] {
    self: akka.http.scaladsl.model.Uri.Query ⇒
    override def newBuilder: mutable.Builder[(String, String), akka.http.scaladsl.model.Uri.Query] = akka.http.scaladsl.model.Uri.Query.newBuilder
  }

}
