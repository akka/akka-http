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

import scala.reflect.macros.whitebox
import akka.parboiled2.support.hlist.HList

import scala.collection.immutable

trait DynamicRuleDispatchMacro { _: DynamicRuleDispatch.type =>

  /**
   * Implements efficient runtime dispatch to a predefined set of parser rules.
   * Given a number of rule names this macro-supported method creates a `DynamicRuleDispatch` instance along with
   * a sequence of the given rule names.
   * Note that there is no reflection involved and compilation will fail, if one of the given rule names
   * does not constitute a method of parser type `P` or has a type different from `RuleN[L]`.
   */
  def apply[P <: Parser, L <: HList](ruleNames: String*): (DynamicRuleDispatch[P, L], immutable.Seq[String]) = macro DynamicRuleDispatch.__create[P, L]

  ///////////////////// INTERNAL ////////////////////////

  def __create[P <: Parser, L <: HList](c: whitebox.Context)(ruleNames: c.Expr[String]*)(implicit P: c.WeakTypeTag[P], L: c.WeakTypeTag[L]): c.Expr[(DynamicRuleDispatch[P, L], immutable.Seq[String])] = {
    import c.universe._
    val names = ruleNames.map {
      _.tree match {
        case Literal(Constant(s: String)) => s
        case x                            => c.abort(x.pos, s"Invalid `String` argument `x`, only `String` literals are supported!")
      }
    }

    def ruleEntry(name: String): Tree =
      q"""($name, new RuleRunner[$P, $L] {
                def apply(handler: DynamicRuleHandler[$P, $L]): handler.Result = {
                  val p = handler.parser
                  p.__run[$L](p.${TermName(name).encodedName.toTermName})(handler)
                }
              })"""
    val ruleEntries: Seq[Tree] = names.map(ruleEntry(_))

    c.Expr[(DynamicRuleDispatch[P, L], immutable.Seq[String])] {
      q"""val map: Map[String, RuleRunner[$P, $L]] = Map(..$ruleEntries)
          val drd =
            new akka.parboiled2.DynamicRuleDispatch[$P, $L] {
              def lookup(ruleName: String): Option[RuleRunner[$P, $L]] =
                map.get(ruleName)
            }
          (drd, scala.collection.immutable.Seq(..$ruleNames))"""
    }
  }
}
