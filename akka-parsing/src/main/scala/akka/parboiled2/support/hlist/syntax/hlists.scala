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
package syntax

import akka.parboiled2.support.hlist.ops.hlist.Reverse

/**
 * Carrier for `HList` operations.
 *
 * These methods are implemented here and pimped onto the minimal `HList` types to avoid issues that would otherwise be
 * caused by the covariance of `::[H, T]`.
 *
 * @author Miles Sabin
 */
final class HListOps[L <: HList](l: L) extends Serializable {

  /**
   * Prepend the argument element to this `HList`.
   */
  def ::[H](h: H): H :: L = akka.parboiled2.support.hlist.::(h, l)

  /**
   * Reverses this `HList`.
   */
  def reverse(implicit reverse: Reverse[L]): reverse.Out = reverse(l)
}
