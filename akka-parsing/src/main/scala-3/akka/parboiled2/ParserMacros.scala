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

import support.hlist.HList

private[parboiled2] trait ParserMacroMethods { parser: Parser =>

  /** Converts a compile-time only rule definition into the corresponding rule method implementation.
    */
  inline def rule[I <: HList, O <: HList](inline r: Rule[I, O]): Rule[I, O] = ${ ParserMacros.ruleImpl('parser, 'r) }

  /** Converts a compile-time only rule definition into the corresponding rule method implementation
    * with an explicitly given name.
    */
  inline def namedRule[I <: HList, O <: HList](name: String)(inline r: Rule[I, O]): Rule[I, O] = ${
    ParserMacros.nameRuleImpl('parser, 'name, 'r)
  }

}

private[parboiled2] trait RuleRunnable {

  /** THIS IS NOT PUBLIC API and might become hidden in future. Use only if you know what you are doing!
    */
  extension [L <: HList](inline rule: RuleN[L]) {
    inline def run()(using scheme: Parser.DeliveryScheme[L]): scheme.Result =
      ${ ParserMacros.runImpl[L, scheme.Result]()('rule, 'scheme) }
  }
}

object ParserMacros {
  import scala.quoted._
  import scala.compiletime._

  // TODO: the `R` type parameter is a workaround for https://github.com/lampepfl/dotty/issues/13376
  // Discussion at https://github.com/sirthias/parboiled2/pull/274#issuecomment-904926294
  def runImpl[L <: HList: Type, R: Type]()(ruleExpr: Expr[RuleN[L]], schemeExpr: Expr[Parser.DeliveryScheme[L]])(using
      Quotes
  ): Expr[R] = {
    import quotes.reflect.*

    /*
    the `rule.run()` macro supports two scenarios (`rule` has type `RuleN[L]`):

    1. someParserExpression.rule[targs](args).run()(deliveryScheme)
       is re-written to
       { val p = someParserExpression
         p.__run[L](p.rule[targs](args))(deliveryScheme) }

    2. Within a Parser subclass:
         rule(...).run()(deliveryScheme)
       is re-written to
         this.__run[L](rule(...))(deliveryScheme)
       Note that `rule` is also a macro, we work with the macro expansion of the `rule` call.
     */

    case class RuleFromParser(parser: Term, rule: Symbol, targs: List[TypeTree], argss: List[List[Term]]) {
      def ruleCall[P](localParser: Expr[P]): Expr[RuleN[L]] = {
        val r = Select(localParser.asTerm, rule)
        argss.foldLeft(if (targs.isEmpty) r else TypeApply(r, targs))((t, args) => Apply(t, args)).asExprOf[RuleN[L]]
      }
    }

    object RuleFromParser {
      def dissect(t: Term, targs: List[TypeTree], argss: List[List[Term]]): (Term, List[TypeTree], List[List[Term]]) =
        t.underlyingArgument match {
          case Apply(f, args)      => dissect(f, targs, args :: argss)
          case TypeApply(f, targs) => dissect(f, targs, argss)
          case t                   => (t, targs, argss)
        }

      def unapply(t: Term): Option[RuleFromParser] = dissect(t, Nil, Nil) match {
        case (rule @ Select(parser, _), targs, argss) if parser.tpe <:< TypeRepr.of[Parser] =>
          Some(RuleFromParser(parser, rule.symbol, targs, argss))
        case _ => None
      }
    }

    def isRuleMacro(sym: Symbol) =
      sym.owner == TypeRepr.of[ParserMacroMethods].typeSymbol &&
        (sym.name == "rule" || sym.name == "namedRule")

    ruleExpr.asTerm match {
      case RuleFromParser(rule) =>
        rule.parser.tpe.asType match {
          case '[pT] =>
            // TODO: the parser type `pT` is not bounded by `<: Parser`, not sure how to do that.
            // This is why `asInstanceOf[Parser]` is needed below
            val parserExpr = rule.parser.asExprOf[pT]
            '{
              val p: pT = $parserExpr
              p.asInstanceOf[Parser].__run[L](${ rule.ruleCall('p) })($schemeExpr).asInstanceOf[R]
            }
        }
      case Inlined(_, _, Inlined(Some(ruleMacro), List(ValDef(_, _, Some(parserThis))), rule))
          if rule.tpe <:< TypeRepr.of[RuleX] && isRuleMacro(ruleMacro.symbol) =>
        // The `Inlined` tree for the `rule` macro has a binding for the parser instance.
        // TODO: we re-use the rhs of that binding (parserThis), I didn't manage to create the right This() tree.
        '{ ${ parserThis.asExprOf[Parser] }.__run[L]($ruleExpr)($schemeExpr).asInstanceOf[R] }
      case r =>
        report.error(s"""Cannot rewrite `myRule.run()` call for rule: ${ruleExpr.show}
                        |`myRule` needs to be either of the form `someParser.someRule[targs](args)`
                        |or it needs to be a `rule(...)` definition within a Parser subclass.""".stripMargin)
        throw new MatchError(r)
    }
  }

  def ruleImpl[I <: HList: Type, O <: HList: Type](parser: Expr[Parser], r: Expr[Rule[I, O]])(using
      Quotes
  ): Expr[Rule[I, O]] = {
    import quotes.reflect.*
    nameRuleImpl(parser, Expr(Symbol.spliceOwner.owner.name), r)
  }

  def nameRuleImpl[I <: HList: Type, O <: HList: Type](parser: Expr[Parser], name: Expr[String], r: Expr[Rule[I, O]])(
      using Quotes
  ): Expr[Rule[I, O]] = {
    import quotes.reflect.*

    val ctx    = new support.OpTreeContext(parser)
    val opTree = ctx.topLevel(ctx.deconstruct(r), name)

    '{
      def wrapped: Boolean = ${ opTree.render(wrapped = true) }
      val matched =
        if ($parser.__inErrorAnalysis) wrapped
        else ${ opTree.render(wrapped = false) }
      if (matched) akka.parboiled2.Rule.asInstanceOf[Rule[I, O]] else null
    }
  }
}
