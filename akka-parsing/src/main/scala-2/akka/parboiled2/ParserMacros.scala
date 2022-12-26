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

import akka.parboiled2.support.OpTreeContext
import akka.parboiled2.support.hlist.HList

private[parboiled2] trait ParserMacroMethods {

  /**
   * Converts a compile-time only rule definition into the corresponding rule method implementation.
   */
  def rule[I <: HList, O <: HList](r: Rule[I, O]): Rule[I, O] = macro ParserMacros.ruleImpl[I, O]

  /**
   * Converts a compile-time only rule definition into the corresponding rule method implementation
   * with an explicitly given name.
   */
  def namedRule[I <: HList, O <: HList](name: String)(r: Rule[I, O]): Rule[I, O] = macro ParserMacros.namedRuleImpl[I, O]

}

private[parboiled2] trait RuleRunnable {

  /**
   * THIS IS NOT PUBLIC API and might become hidden in future. Use only if you know what you are doing!
   */
  implicit class Runnable[L <: HList](rule: RuleN[L]) {
    def run()(implicit scheme: Parser.DeliveryScheme[L]): scheme.Result = macro ParserMacros.runImpl[L]
  }
}

object ParserMacros {
  import scala.reflect.macros.whitebox.Context

  /**
   * THIS IS NOT PUBLIC API and might become hidden in future. Use only if you know what you are doing!
   */
  type RunnableRuleContext[L <: HList] = Context { type PrefixType = Rule.Runnable[L] }

  def runImpl[L <: HList: c.WeakTypeTag](
    c: RunnableRuleContext[L]
  )()(scheme: c.Expr[Parser.DeliveryScheme[L]]): c.Expr[scheme.value.Result] = {
    import c.universe._
    val runCall = c.prefix.tree match {
      case q"parboiled2.this.Rule.Runnable[$l]($ruleExpr)" =>
        ruleExpr match {
          case q"$p.$r[..$ts](...$argss)" if p.tpe <:< typeOf[Parser] =>
            q"val p = $p; p.__run[$l](p.$r[..$ts](...$argss))($scheme)"
          case rule if rule.tpe <:< typeOf[RuleX] => q"__run[$l]($ruleExpr)($scheme)"
          case x                                  => c.abort(x.pos, "Illegal `.run()` call base: " + x)
        }
      case x => c.abort(x.pos, "Illegal `Runnable.apply` call: " + x)
    }
    c.Expr[scheme.value.Result](runCall)
  }

  /**
   * THIS IS NOT PUBLIC API and might become hidden in future. Use only if you know what you are doing!
   */
  type ParserContext = Context { type PrefixType = Parser }

  def ruleImpl[I <: HList: ctx.WeakTypeTag, O <: HList: ctx.WeakTypeTag](
    ctx: ParserContext
  )(r: ctx.Expr[Rule[I, O]]): ctx.Expr[Rule[I, O]] = {
    import ctx.universe._
    namedRuleImpl(ctx)(ctx.Expr[String](Literal(Constant(ctx.internal.enclosingOwner.name.decodedName.toString))))(r)
  }

  def namedRuleImpl[I <: HList: ctx.WeakTypeTag, O <: HList: ctx.WeakTypeTag](
    ctx: ParserContext
  )(name: ctx.Expr[String])(r: ctx.Expr[Rule[I, O]]): ctx.Expr[Rule[I, O]] = {
    val opTreeCtx = new OpTreeContext[ctx.type] { val c: ctx.type = ctx }
    val opTree = opTreeCtx.RuleCall(Left(opTreeCtx.OpTree(r.tree)), name.tree)
    import ctx.universe._
    val ruleTree = q"""
      def wrapped: Boolean = ${opTree.render(wrapped = true)}
      val matched =
        if (__inErrorAnalysis) wrapped
        else ${opTree.render(wrapped = false)}
      if (matched) akka.parboiled2.Rule else null""" // we encode the "matched" boolean as 'ruleResult ne null'

    reify(ctx.Expr[RuleX](ruleTree).splice.asInstanceOf[Rule[I, O]])
  }
}
