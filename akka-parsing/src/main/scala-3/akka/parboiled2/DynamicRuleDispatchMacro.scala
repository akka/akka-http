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

import scala.collection.immutable

trait DynamicRuleDispatchMacro { self: DynamicRuleDispatch.type =>

  /** Implements efficient runtime dispatch to a predefined set of parser rules.
    * Given a number of rule names this macro-supported method creates a `DynamicRuleDispatch` instance along with
    * a sequence of the given rule names.
    * Note that there is no reflection involved and compilation will fail, if one of the given rule names
    * does not constitute a method of parser type `P` or has a type different from `RuleN[L]`.
    */
  inline def apply[P <: Parser, L <: HList](
      inline ruleNames: String*
  ): (DynamicRuleDispatch[P, L], immutable.Seq[String]) =
    ${ DynamicRuleDispatch.__create[P, L]('ruleNames) }

  import scala.quoted._
  def __create[P <: Parser: Type, L <: HList: Type](
      ruleNames: Expr[Seq[String]]
  )(using Quotes): Expr[(DynamicRuleDispatch[P, L], immutable.Seq[String])] = {
    import quotes.reflect._

    val names: Seq[String] = ruleNames match {
      case Varargs(Exprs(args)) => args.sorted
    }

    def ruleEntry(name: String): Expr[(String, RuleRunner[P, L])] = '{
      val runner = new RuleRunner[P, L] {
        def apply(handler: DynamicRuleHandler[P, L]): handler.Result = {
          val p = handler.parser
          p.__run[L](${Select.unique('{ handler.parser }.asTerm, name).asExprOf[RuleN[L]]})(handler)
        }
      }
      (${Expr(name)}, runner)
    }
    val ruleEntries: Expr[Seq[(String, RuleRunner[P, L])]] = Expr.ofSeq(names.map(ruleEntry(_)))

    '{
      val map: Map[String, RuleRunner[P, L]] = Map($ruleEntries*)
      val drd =
        new akka.parboiled2.DynamicRuleDispatch[P, L] {
          def lookup(ruleName: String): Option[RuleRunner[P, L]] =
            map.get(ruleName)
        }

      (drd, $ruleNames)
    }
  }
}
