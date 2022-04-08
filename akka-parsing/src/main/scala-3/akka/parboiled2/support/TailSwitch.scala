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

package akka.parboiled2.support

import hlist._

import scala.annotation.implicitNotFound

/**
  * type-level implementation of this logic:
  *   Out =
  *     R                      if T has a tail of type L
  *     (L dropRight T) ::: R  if L has a tail of type T
  */
@implicitNotFound("Illegal rule composition")
sealed trait TailSwitch[L <: HList, T <: HList, R <: HList] {
  type Out <: HList
}
object TailSwitch {
  type Reverse0[Acc <: HList, L <: HList] <: HList = L match {
    case HNil     => Acc
    case ::[h, t] => Reverse0[h :: Acc, t]
  }

  type Reverse1[L <: HList] <: HList = L match {
    case HNil     => HNil
    case ::[h, t] => Reverse0[h :: HNil, t]
  }

  type Prepend0[A <: HList, B <: HList] <: HList = A match {
    case HNil     => B
    case ::[h, t] => ::[h, Prepend0[t, B]]
  }

  // type-level implementation of this algorithm:
  //   @tailrec def rec(L, LI, T, TI, R, RI) =
  //     if (TI <: L) R
  //     else if (LI <: T) RI.reverse ::: R
  //     else if (LI <: HNil) rec(L, HNil, T, TI.tail, R, RI)
  //     else if (TI <: HNil) rec(L, LI.tail, T, HNil, R, LI.head :: RI)
  //     else rec(L, LI.tail, T, TI.tail, R, LI.head :: RI)
  //   rec(L, L, T, T, R, HNil)
  type TailSwitch0[L <: HList, LI <: HList, T <: HList, TI <: HList, R <: HList, RI <: HList] <: HList = TI match {
    case L => R
    case _ =>
      LI match {
        case T => Prepend0[Reverse1[RI], R]
        case HNil =>
          TI match {
            case ::[_, t] => TailSwitch0[L, HNil, T, t, R, RI]
          }
        case ::[h, t] =>
          TI match {
            case HNil      => TailSwitch0[L, t, T, HNil, R, h :: RI]
            case ::[_, tt] => TailSwitch0[L, t, T, tt, R, h :: RI]
          }
      }
  }

  type Aux[L <: HList, LI <: HList, T <: HList, TI <: HList, R <: HList, RI <: HList, Out0] =
    TailSwitch[L, T, R] { type Out = Out0 }

  implicit def tailSwitch[L <: _ :: _, T <: _ :: _, R <: HList]
      : TailSwitch[L, T, R] { type Out = TailSwitch0[L, L, T, T, R, HNil] } = `n/a`
  /// Optimisations to reduce compilation times to something tolerable
  implicit def tailSwitch0: TailSwitch[HNil, HNil, HNil] { type Out = HNil } = `n/a`
  implicit def tailSwitch1[T <: _ :: _]: TailSwitch[HNil, T, HNil] { type Out = HNil } = `n/a`
  implicit def tailSwitch2[L <: _ :: _, R <: _ :: _]: TailSwitch[L, HNil, R] { type Out = Prepend0[L, R] } = `n/a`
  implicit def tailSwitch3[L <: _ :: _]: TailSwitch[L, HNil, HNil] { type Out = L } = `n/a`
  implicit def tailSwitch4[T <: HList, R <: _ :: _]: TailSwitch[HNil, T, R] { type Out = R } = `n/a`
}
