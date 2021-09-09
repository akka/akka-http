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

package akka.parboiled2

import akka.parboiled2.support.hlist.HList

/**
 * An application needs to implement this interface to receive the result
 * of a dynamic parsing run.
 * Often times this interface is directly implemented by the Parser class itself
 * (even though this is not a requirement).
 */
trait DynamicRuleHandler[P <: Parser, L <: HList] extends Parser.DeliveryScheme[L] {
  def parser: P
  def ruleNotFound(ruleName: String): Result
}

/**
 * Runs one of the rules of a parser instance of type `P` given the rules name.
 * The rule must have type `RuleN[L]`.
 */
trait DynamicRuleDispatch[P <: Parser, L <: HList] {
  def apply(handler: DynamicRuleHandler[P, L], ruleName: String): handler.Result =
    lookup(ruleName).map(_(handler)).getOrElse(handler.ruleNotFound(ruleName)).asInstanceOf[handler.Result] // FIXME: something wrong with Scala 3 type inference

  def lookup(ruleName: String): Option[RuleRunner[P, L]]
}

trait RuleRunner[P <: Parser, L <: HList] {
  def apply(handler: DynamicRuleHandler[P, L]): handler.Result
}

object DynamicRuleDispatch extends DynamicRuleDispatchMacro
