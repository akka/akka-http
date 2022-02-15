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

trait HListable[T] {
  type Out <: HList
}

object HListable {
  type HL0[T] <: HList = T match {
    case Unit     => HNil
    case HNil     => HNil
    case ::[a, b] => ::[a, b]
    case _        => T :: HNil
  }

  implicit def calc[T]: HListable[T] { type Out = HL0[T] } = `n/a`
}
