/*
 * Copyright 2009-2019 Mathias Doenitz
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package akka.parboiled2.support.hlist

/**
 * `HList` ADT base trait.
 *
 * @author Miles Sabin
 */
sealed trait HList extends Product with Serializable

/**
 * Non-empty `HList` element type.
 *
 * @author Miles Sabin
 */
final case class ::[+H, +T <: HList](head: H, tail: T) extends HList {

  override def toString: String = head match {
    case _: ::[_, _] => s"($head) :: $tail"
    case _           => s"$head :: $tail"
  }
}

/**
 * Empty `HList` element type.
 *
 * @author Miles Sabin
 */
sealed trait HNil extends HList {
  def ::[H](h: H): H :: HNil = new ::(h, this)
  override def toString = "HNil"
}

/**
 * Empty `HList` value.
 *
 * @author Miles Sabin
 */
case object HNil extends HNil

object HList {
  import akka.parboiled2.support.hlist.syntax.HListOps

  def apply(): HNil.type = HNil

  def apply[T](t: T): T :: HNil = t :: HNil

  implicit def hlistOps[L <: HList](l: L): HListOps[L] = new HListOps(l)

  /**
   * Convenience aliases for HList :: and List :: allowing them to be used together within match expressions.
   */
  object ListCompat {
    val :: = scala.collection.immutable.::
    val #: = akka.parboiled2.support.hlist.::
  }
}
