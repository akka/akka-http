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

import akka.parboiled2._
import akka.parboiled2.support.hlist.HList

import scala.quoted._
import scala.annotation.tailrec

import scala.quoted._
import scala.annotation.tailrec

class OpTreeContext(parser: Expr[Parser])(using Quotes) {
  import quotes.reflect.*

  sealed trait OpTree {
    def render(wrapped: Boolean): Expr[Boolean]
  }

  sealed abstract class NonTerminalOpTree extends OpTree {
    def bubbleUp(e: Expr[akka.parboiled2.Parser#TracingBubbleException], start: Expr[Int]): Expr[Nothing]

    // renders a Boolean Tree
    def render(wrapped: Boolean): Expr[Boolean] =
      if (wrapped) '{
        val start = $parser.cursor
        try ${ renderInner('start, wrapped) } catch {
          case e: akka.parboiled2.Parser#TracingBubbleException => ${ bubbleUp('e, 'start) }
        }
      }
      else renderInner(Expr(-1) /* dummy, won't be used */, wrapped)

    // renders a Boolean Tree
    protected def renderInner(start: Expr[Int], wrapped: Boolean): Expr[Boolean]
  }

  sealed abstract class DefaultNonTerminalOpTree extends NonTerminalOpTree {
    def bubbleUp(e: Expr[akka.parboiled2.Parser#TracingBubbleException], start: Expr[Int]): Expr[Nothing] = '{
      $e.bubbleUp($ruleTraceNonTerminalKey, $start)
    }
    def ruleTraceNonTerminalKey: Expr[RuleTrace.NonTerminalKey]
  }

  sealed abstract class TerminalOpTree extends OpTree {
    def bubbleUp: Expr[Nothing] = '{ $parser.__bubbleUp($ruleTraceTerminal) }
    def ruleTraceTerminal: Expr[RuleTrace.Terminal]

    final def render(wrapped: Boolean): Expr[Boolean] =
      if (wrapped) '{
        try ${ renderInner(wrapped) } catch { case akka.parboiled2.Parser.StartTracingException => $bubbleUp }
      }
      else renderInner(wrapped)

    protected def renderInner(wrapped: Boolean): Expr[Boolean]
  }
  sealed abstract private class PotentiallyNamedTerminalOpTree(arg: Term) extends TerminalOpTree {
    override def bubbleUp: Expr[Nothing] =
      callName(arg) match {
        case Some(name) =>
          '{ $parser.__bubbleUp(RuleTrace.NonTerminal(RuleTrace.Named(${ Expr(name) }), 0) :: Nil, $ruleTraceTerminal) }
        case None => super.bubbleUp
      }
  }

  def Sequence(lhs: OpTree, rhs: OpTree): Sequence =
    lhs -> rhs match {
      case (Sequence(lops), Sequence(rops)) => Sequence(lops ++ rops)
      case (Sequence(lops), _)              => Sequence(lops :+ rhs)
      case (_, Sequence(ops))               => Sequence(lhs +: ops)
      case _                                => Sequence(Seq(lhs, rhs))
    }

  case class Sequence(ops: Seq[OpTree]) extends DefaultNonTerminalOpTree {
    require(ops.size >= 2)
    override def ruleTraceNonTerminalKey = '{ RuleTrace.Sequence }
    override def renderInner(start: quoted.Expr[Int], wrapped: Boolean): Expr[Boolean] =
      ops
        .map(_.render(wrapped))
        .reduceLeft((l, r) => '{ val ll = $l; if (ll) $r else false })
  }

  case class Cut(lhs: OpTree, rhs: OpTree) extends DefaultNonTerminalOpTree {
    override def ruleTraceNonTerminalKey = '{ RuleTrace.Cut }
    override def renderInner(start: Expr[Int], wrapped: Boolean): Expr[Boolean] = '{
      var matched = ${ lhs.render(wrapped) }
      if (matched) {
        matched = ${ rhs.render(wrapped) }
        if (!matched) throw akka.parboiled2.Parser.CutError
        true
      } else false
    } // work-around for https://issues.scala-lang.org/browse/SI-8657
  }

  def FirstOf(lhs: OpTree, rhs: OpTree): FirstOf =
    lhs -> rhs match {
      case (FirstOf(lops), FirstOf(rops)) => FirstOf(lops ++ rops)
      case (FirstOf(lops), _)             => FirstOf(lops :+ rhs)
      case (_, FirstOf(ops))              => FirstOf(lhs +: ops)
      case _                              => FirstOf(Seq(lhs, rhs))
    }
  case class FirstOf(ops: Seq[OpTree]) extends DefaultNonTerminalOpTree {
    def ruleTraceNonTerminalKey = '{ RuleTrace.FirstOf }

    def renderInner(start: Expr[Int], wrapped: Boolean): Expr[Boolean] =
      '{
        val mark = $parser.__saveState
        ${
          ops
            .map(_.render(wrapped))
            .reduceLeft((l0, r) =>
              '{
                val l = $l0
                if (!l) {
                  $parser.__restoreState(mark)
                  $r
                } else
                  true // work-around for https://issues.scala-lang.org/browse/SI-8657", FIXME: still valid for dotty?
              }
            )
        }
      }
  }

  sealed abstract class WithSeparator extends DefaultNonTerminalOpTree {
    def withSeparator(sep: Separator): OpTree
  }

  case class ZeroOrMore(op: OpTree, collector: Collector, separator: Separator = null) extends WithSeparator {
    def withSeparator(sep: Separator) = copy(separator = sep)
    def ruleTraceNonTerminalKey       = '{ RuleTrace.ZeroOrMore }

    def renderInner(start: Expr[Int], wrapped: Boolean): Expr[Boolean] =
      collector.withCollector { coll =>
        '{
          @ _root_.scala.annotation.tailrec
          def rec(mark: akka.parboiled2.Parser.Mark): akka.parboiled2.Parser.Mark = {
            val matched = ${ op.render(wrapped) }
            if (matched) {
              ${ coll.popToBuilder }
              ${
                if (separator eq null) '{ rec($parser.__saveState) }
                else
                  '{
                    val m = $parser.__saveState
                    if (${ separator(wrapped) }) rec(m) else m
                  }
              }
            } else mark
          }

          $parser.__restoreState(rec($parser.__saveState))
          ${ coll.pushBuilderResult }
          true
        }
      }
  }
  case class OneOrMore(op: OpTree, collector: Collector, separator: Separator = null) extends WithSeparator {
    def withSeparator(sep: Separator) = copy(separator = sep)
    def ruleTraceNonTerminalKey       = '{ RuleTrace.OneOrMore }

    def renderInner(start: Expr[Int], wrapped: Boolean): Expr[Boolean] =
      collector.withCollector { coll =>
        '{
          @ _root_.scala.annotation.tailrec
          def rec(mark: akka.parboiled2.Parser.Mark): akka.parboiled2.Parser.Mark = {
            val matched = ${ op.render(wrapped) }
            if (matched) {
              ${ coll.popToBuilder }
              ${
                if (separator eq null) '{ rec($parser.__saveState) }
                else
                  '{
                    val m = $parser.__saveState
                    if (${ separator(wrapped) }) rec(m) else m
                  }
              }
            } else mark
          }

          val firstMark = $parser.__saveState
          val mark      = rec(firstMark)
          mark != firstMark && { // FIXME: almost the same as ZeroOrMore and should be combined
            $parser.__restoreState(mark)
            ${ coll.pushBuilderResult }
            true
          }
        }
      }
  }

  case class Optional(op: OpTree, collector: Collector) extends DefaultNonTerminalOpTree {
    def ruleTraceNonTerminalKey = '{ RuleTrace.Optional }
    def renderInner(start: Expr[Int], wrapped: Boolean): Expr[Boolean] =
      collector.withCollector { coll =>
        '{
          val mark    = $parser.__saveState
          val matched = ${ op.render(wrapped) }
          if (matched) {
            ${ coll.pushSomePop }
          } else {
            $parser.__restoreState(mark)
            ${ coll.pushNone }
          }
          true
        }
      }
  }

  trait MinMaxSupplier {
    def apply[T: Type](f: (Expr[Int], Expr[Int]) => Expr[T]): Expr[T]
  }
  object MinMaxSupplier {
    def constant(n: Expr[Int]): MinMaxSupplier =
      new MinMaxSupplier {
        override def apply[T: Type](f: (Expr[Int], Expr[Int]) => Expr[T]): Expr[T] =
          '{
            val min = $n
            val max = min

            ${ f('min, 'max) }
          }
      }

    def range(rangeExpr: Expr[Range]): MinMaxSupplier =
      new MinMaxSupplier {
        override def apply[T: Type](f: (Expr[Int], Expr[Int]) => Expr[T]): Expr[T] =
          '{
            val r   = $rangeExpr
            val min = r.min
            val max = r.max

            ${ f('min, 'max) }
          }
      }
  }

  def Int2NTimes(
      n: Expr[Int],
      op: OpTree,
      withMinMax: MinMaxSupplier,
      collector: Collector,
      separator: Separator
  ): OpTree =
    n.asTerm match {
      case Literal(IntConstant(i)) =>
        if (i <= 0) reportError("`x` in `x.times` must be positive", n)
        else if (i == 1) op
        else Times(op, withMinMax, collector, separator)
      case _ =>
        Times(op, withMinMax, collector, separator)
    }

  def Range2NTimes(
      range: Expr[Range],
      op: OpTree,
      withMinMax: MinMaxSupplier,
      collector: Collector,
      separator: Separator
  ): OpTree = {
    range match {
      case '{ scala.Predef.intWrapper($mn).to($mx) } =>
        mn.asTerm match {
          case Literal(IntConstant(min)) if min <= 0 =>
            reportError("`min` in `(min to max).times` must be positive", mn)
          case _ => ()
        }
        mx.asTerm match {
          case Literal(IntConstant(max)) if max <= 0 =>
            reportError("`max` in `(min to max).times` must be positive", mx)
          case _ => ()
        }
        (mn.asTerm, mx.asTerm) match {
          case (Literal(IntConstant(min)), Literal(IntConstant(max))) if max < min =>
            reportError("`max` in `(min to max).times` must be >= `min`", mx)
          case _ => ()
        }
        Times(op, withMinMax, collector, separator)
      case _ =>
        reportError("Invalid base expression for `.times(...)`: " + range.show, range)
    }
  }

  case class Times(
      op: OpTree,
      withMinMax: MinMaxSupplier,
      collector: Collector,
      separator: Separator
  ) extends WithSeparator {
    def withSeparator(sep: Separator) = copy(separator = sep)
    def ruleTraceNonTerminalKey       = withMinMax((min, max) => '{ akka.parboiled2.RuleTrace.Times($min, $max) })

    def renderInner(start: Expr[Int], wrapped: Boolean): Expr[Boolean] =
      collector.withCollector { coll =>
        withMinMax { (minE, maxE) =>
          '{
            val min = $minE
            val max = $maxE
            require(min <= max, "`max` in `(min to max).times` must be >= `min`")

            @ _root_.scala.annotation.tailrec
            def rec(count: Int, mark: akka.parboiled2.Parser.Mark): Boolean = {
              val matched = ${ op.render(wrapped) }
              if (matched) {
                ${ coll.popToBuilder }
                if (count < max) ${
                  if (separator eq null) '{ rec(count + 1, $parser.__saveState) }
                  else
                    '{
                      val m = $parser.__saveState
                      if (${ separator(wrapped) }) rec(count + 1, m)
                      else (count >= min) && { $parser.__restoreState(m); true }
                    }
                }
                else true

              } else (count > min) && { $parser.__restoreState(mark); true }
            }

            (max <= 0) || rec(1, $parser.__saveState) && { ${ coll.pushBuilderResult }; true }
          }
        }
      }
  }

  case class AndPredicate(op: OpTree) extends DefaultNonTerminalOpTree {
    def ruleTraceNonTerminalKey = '{ RuleTrace.AndPredicate }
    def renderInner(start: Expr[Int], wrapped: Boolean): Expr[Boolean] =
      '{
        val mark    = $parser.__saveState
        val matched = ${ op.render(wrapped) }
        $parser.__restoreState(mark)
        matched
      }
  }

  case class NotPredicate(op: OpTree) extends OpTree {

    def render(wrapped: Boolean): Expr[Boolean] = {
      def unwrappedExpr(setMatchEnd: Option[Expr[Int] => Expr[Unit]]): Expr[Boolean] = '{
        val mark    = $parser.__saveState
        val saved   = $parser.__enterNotPredicate()
        val matched = ${ op.render(wrapped) }
        $parser.__exitNotPredicate(saved)
        ${
          setMatchEnd match {
            case Some(matchEndSetter) => matchEndSetter('{ $parser.cursor })
            case None                 => '{}
          }
        }
        $parser.__restoreState(mark)
        !matched
      }

      if (wrapped) {
        val base = op match {
          case x: TerminalOpTree   => '{ RuleTrace.NotPredicate.Terminal(${ x.ruleTraceTerminal }) }
          case x: RuleCall         => '{ RuleTrace.NotPredicate.RuleCall(${ x.calleeNameTree }) }
          case x: StringMatch      => '{ RuleTrace.NotPredicate.Named(s"\"${${ x.stringTree }}\"") }
          case x: IgnoreCaseString => '{ RuleTrace.NotPredicate.Named(s"\"${${ x.stringTree }}\"") }
          case x: Named            => '{ RuleTrace.NotPredicate.Named(s"\"${${ x.stringExpr }}\"") }
          case _                   => '{ RuleTrace.NotPredicate.Anonymous }
        }
        '{
          var matchEnd = 0
          try ${ unwrappedExpr(Some(v => '{ matchEnd = $v })) } || $parser.__registerMismatch()
          catch {
            case Parser.StartTracingException =>
              $parser.__bubbleUp {
                RuleTrace.NotPredicate($base, matchEnd - $parser.cursor)
              }
          }
        }
      } else unwrappedExpr(None)
    }
  }

  private def expandLambda(body: Term, wrapped: Boolean): Expr[Boolean] = {
    def popToVals(valdefs: List[ValDef]): List[Statement] = {
      def convertOne(v: ValDef): ValDef =
        v.tpt.tpe.asType match {
          case '[t] => ValDef.copy(v)(v.name, v.tpt, Some('{ $parser.valueStack.pop().asInstanceOf[t] }.asTerm))
        }

      valdefs.map(convertOne).reverse
    }

    body match {
      case Lambda(args, body) =>
        def rewrite(tree: Term): Term =
          tree.tpe.asType match {
            case '[Rule[_, _]] => expand(tree, wrapped).asInstanceOf[Term]
            case _ =>
              tree match {
                case Block(statements, res) => block(statements, rewrite(res))
                case x                      => '{ $parser.__push(${ x.asExpr }) }.asTerm
              }
          }
        // do a beta reduction, using the parameter definitions as stubs for variables
        // that hold values popped from the stack
        block(popToVals(args), rewrite(body)).asExprOf[Boolean]
    }
  }

  private case class Action(body: Term, ts: List[TypeTree]) extends DefaultNonTerminalOpTree {
    def ruleTraceNonTerminalKey = '{ RuleTrace.Action }

    def renderInner(start: Expr[Int], wrapped: Boolean): Expr[Boolean] = expandLambda(body, wrapped)
  }

  case class RunAction(body: Expr[_]) extends DefaultNonTerminalOpTree {
    def ruleTraceNonTerminalKey = '{ RuleTrace.Run }

    def renderInner(start: Expr[Int], wrapped: Boolean): Expr[Boolean] =
      body.asTerm.tpe.asType match {
        case '[Rule[_, _]]                => expand(body, wrapped)
        case '[t1 => r]                   => expandLambda(body.asTerm, wrapped)
        case '[(t1, t2) => r]             => expandLambda(body.asTerm, wrapped)
        case '[(t1, t2, t3) => r]         => expandLambda(body.asTerm, wrapped)
        case '[(t1, t2, t3, t4) => r]     => expandLambda(body.asTerm, wrapped)
        case '[(t1, t2, t3, t4, t5) => r] => expandLambda(body.asTerm, wrapped)
        case '[x]                         => '{ $body; true }
      }
  }

  case class SemanticPredicate(flagTree: Expr[Boolean]) extends TerminalOpTree {
    def ruleTraceTerminal = '{ akka.parboiled2.RuleTrace.SemanticPredicate }

    override def renderInner(wrapped: Boolean): Expr[Boolean] =
      if (wrapped) '{ $flagTree || $parser.__registerMismatch() }
      else flagTree
  }

  case class Capture(op: OpTree) extends DefaultNonTerminalOpTree {
    def ruleTraceNonTerminalKey = '{ RuleTrace.Capture }
    def renderInner(start: Expr[Int], wrapped: Boolean): Expr[Boolean] =
      '{
        val start1  = ${ if (wrapped) start else '{ $parser.cursor } }
        val matched = ${ op.render(wrapped) }
        if (matched) {
          $parser.valueStack.push($parser.input.sliceString(start1, $parser.cursor))
          true
        } else false
      }
  }

  private case class RunSubParser(fTree: Expr[_]) extends DefaultNonTerminalOpTree {
    def ruleTraceNonTerminalKey = '{ RuleTrace.RunSubParser }
    def renderInner(start: quoted.Expr[Int], wrapped: Boolean): Expr[Boolean] = {
      def rewrite(arg: ValDef, tree: Term): Expr[Boolean] = {
        tree match {
          case Block(statements, res) => block(statements, rewrite(arg, res).asTerm).asExprOf[Boolean]
          case Select(Apply(parserCons, List(consArg @ Ident(_))), rule) if consArg.symbol == arg.symbol =>
            val term = Apply(parserCons, List('{ $parser.__subParserInput }.asTerm))
            term.tpe.asType match {
              case '[p] =>
                '{
                  val __subParser = ${ term.asExprOf[Parser with p] }
                  val offset      = $parser.cursor
                  __subParser.copyStateFrom($parser, offset)
                  try ${ Select.unique('{ __subParser }.asTerm, rule).asExpr } != null
                  finally $parser.copyStateFrom(__subParser, -offset)
                }
            }
          case x => reportError("Illegal runSubParser expr: " + x.show, fTree)
        }
      }
      fTree.asTerm match {
        case Lambda(List(vd @ ValDef(_, _, _)), body) => rewrite(vd, body)
        case x                                        => reportError("Illegal runSubParser expr: " + x.show, fTree)
      }
    }
  }

  private case class PushAction(valueExpr: Expr[_], argType: Type[_]) extends OpTree {
    def render(wrapped: Boolean): Expr[Boolean] = {
      val body =
        argType match {
          case '[Unit]  => valueExpr
          case '[HList] => '{ $parser.valueStack.pushAll($valueExpr.asInstanceOf[HList]) }
          case _        => '{ $parser.valueStack.push($valueExpr) }
        }

      '{
        $body
        true
      }
    }
  }
  private case class DropAction(tpe: Type[_]) extends OpTree {
    def render(wrapped: Boolean): Expr[Boolean] = {
      import support.hlist._
      val body =
        tpe match {
          case '[Unit] => '{}
          case '[HList] =>
            @tailrec def rec(t: Type[_], prefix: Expr[Unit]): Expr[Unit] = t match {
              case '[HNil] => prefix
              case '[h :: t] =>
                rec(Type.of[t], '{ $prefix; $parser.valueStack.pop() })

            }
            rec(tpe, '{})

          case _ => '{ $parser.valueStack.pop() }
        }

      '{
        $body
        true
      }
    }
  }

  private case class RuleCall(call: Either[OpTree, Expr[Rule[_, _]]], calleeNameTree: Expr[String])
      extends NonTerminalOpTree {

    def bubbleUp(e: Expr[Parser#TracingBubbleException], start: Expr[Int]): Expr[Nothing] =
      '{ $e.prepend(RuleTrace.RuleCall, $start).bubbleUp(RuleTrace.Named($calleeNameTree), $start) }

    override def render(wrapped: Boolean): Expr[Boolean] = call match {
      case Left(_)     => super.render(wrapped)
      case Right(rule) => '{ $rule ne null }
    }
    protected def renderInner(start: Expr[Int], wrapped: Boolean): Expr[Boolean] = {
      val Left(value) = call
      value.render(wrapped)
    }
  }

  case class CharMatch(charTree: Expr[Char]) extends TerminalOpTree {
    def ruleTraceTerminal = '{ akka.parboiled2.RuleTrace.CharMatch($charTree) }
    override def renderInner(wrapped: Boolean): Expr[Boolean] = {
      val unwrappedTree = '{
        $parser.cursorChar == $charTree && $parser.__advance()
      }
      if (wrapped) '{ $unwrappedTree && $parser.__updateMaxCursor() || $parser.__registerMismatch() }
      else unwrappedTree
    }
  }

  case class StringMatch(stringTree: Expr[String]) extends OpTree {
    final private val autoExpandMaxStringLength = 8

    override def render(wrapped: Boolean): Expr[Boolean] = {
      def unrollUnwrapped(s: String, ix: Int = 0): Expr[Boolean] =
        if (ix < s.length)
          '{
            if ($parser.cursorChar == ${ Expr(s.charAt(ix)) }) {
              $parser.__advance()
              ${ unrollUnwrapped(s, ix + 1) }
            } else false
          }
        else '{ true }

      def unrollWrapped(s: String, ix: Int = 0): Expr[Boolean] =
        if (ix < s.length) {
          val ch = Expr(s.charAt(ix))
          '{
            if ($parser.cursorChar == $ch) {
              $parser.__advance()
              $parser.__updateMaxCursor()
              ${ unrollWrapped(s, ix + 1) }
            } else {
              try $parser.__registerMismatch()
              catch {
                case akka.parboiled2.Parser.StartTracingException =>
                  import akka.parboiled2.RuleTrace._
                  $parser.__bubbleUp(
                    NonTerminal(akka.parboiled2.RuleTrace.StringMatch($stringTree), -${ Expr(ix) }) :: Nil,
                    akka.parboiled2.RuleTrace.CharMatch($ch)
                  )
              }
            }
          }
        } else '{ true }

      stringTree.asTerm match {
        case Literal(StringConstant(s: String)) if s.length <= autoExpandMaxStringLength =>
          if (s.isEmpty) '{ true }
          else if (wrapped) unrollWrapped(s)
          else unrollUnwrapped(s)
        case _ =>
          if (wrapped) '{ $parser.__matchStringWrapped($stringTree) }
          else '{ $parser.__matchString($stringTree) }
      }
    }
  }

  case class MapMatch(mapTree: Expr[Map[String, Any]], ignoreCaseTree: Expr[Boolean]) extends OpTree {

    override def render(wrapped: Boolean): Expr[Boolean] =
      if (wrapped) '{ $parser.__matchMapWrapped($mapTree, $ignoreCaseTree) }
      else '{ $parser.__matchMap($mapTree, $ignoreCaseTree) }
  }

  case class IgnoreCaseChar(charTree: Expr[Char]) extends TerminalOpTree {
    def ruleTraceTerminal = '{ akka.parboiled2.RuleTrace.IgnoreCaseChar($charTree) }

    override def renderInner(wrapped: Boolean): Expr[Boolean] = {
      val unwrappedTree = '{
        _root_.java.lang.Character.toLowerCase($parser.cursorChar) == $charTree && $parser.__advance()
      }
      if (wrapped) '{ $unwrappedTree && $parser.__updateMaxCursor() || $parser.__registerMismatch() }
      else unwrappedTree
    }
  }

  case class IgnoreCaseString(stringTree: Expr[String]) extends OpTree {
    final private val autoExpandMaxStringLength = 8

    override def render(wrapped: Boolean): Expr[Boolean] =
      def unrollUnwrapped(s: String, ix: Int = 0): Expr[Boolean] =
        if (ix < s.length)
          '{
            if (_root_.java.lang.Character.toLowerCase($parser.cursorChar) == ${ Expr(s.charAt(ix)) }) {
              $parser.__advance()
              ${ unrollUnwrapped(s, ix + 1) }
            } else false
          }
        else '{ true }

      def unrollWrapped(s: String, ix: Int = 0): Expr[Boolean] =
        if (ix < s.length) {
          val ch = Expr(s.charAt(ix))
          '{
            if (_root_.java.lang.Character.toLowerCase($parser.cursorChar) == $ch) {
              $parser.__advance()
              $parser.__updateMaxCursor()
              ${ unrollWrapped(s, ix + 1) }
            } else {
              try $parser.__registerMismatch()
              catch {
                case akka.parboiled2.Parser.StartTracingException =>
                  import akka.parboiled2.RuleTrace._
                  $parser.__bubbleUp(
                    NonTerminal(akka.parboiled2.RuleTrace.IgnoreCaseString($stringTree), -${ Expr(ix) }) :: Nil,
                    akka.parboiled2.RuleTrace.IgnoreCaseChar($ch)
                  )
              }
            }
          }
        } else '{ true }

      stringTree.asTerm match {
        case Literal(StringConstant(s: String)) if s.length <= autoExpandMaxStringLength =>
          if (s.isEmpty) '{ true }
          else if (wrapped) unrollWrapped(s)
          else unrollUnwrapped(s)
        case _ =>
          if (wrapped) '{ $parser.__matchIgnoreCaseStringWrapped($stringTree) }
          else '{ $parser.__matchIgnoreCaseString($stringTree) }
      }
  }

  private case class CharPredicateMatch(predicateTree: Expr[CharPredicate])
      extends PotentiallyNamedTerminalOpTree(predicateTree.asTerm) {
    def ruleTraceTerminal = '{ akka.parboiled2.RuleTrace.CharPredicateMatch($predicateTree) }

    override def renderInner(wrapped: Boolean): Expr[Boolean] = {
      val unwrappedTree = '{ $predicateTree($parser.cursorChar) && $parser.__advance() }
      if (wrapped) '{ $unwrappedTree && $parser.__updateMaxCursor() || $parser.__registerMismatch() }
      else unwrappedTree
    }
  }

  case class AnyOf(stringTree: Expr[String]) extends TerminalOpTree {
    def ruleTraceTerminal = '{ akka.parboiled2.RuleTrace.AnyOf($stringTree) }

    override def renderInner(wrapped: Boolean): Expr[Boolean] = {
      val unwrappedTree = '{ $parser.__matchAnyOf($stringTree) }
      if (wrapped) '{ $unwrappedTree && $parser.__updateMaxCursor() || $parser.__registerMismatch() }
      else unwrappedTree
    }
  }

  case class NoneOf(stringTree: Expr[String]) extends TerminalOpTree {
    def ruleTraceTerminal = '{ akka.parboiled2.RuleTrace.NoneOf($stringTree) }

    override def renderInner(wrapped: Boolean): Expr[Boolean] = {
      val unwrappedTree = '{ $parser.__matchNoneOf($stringTree) }
      if (wrapped) '{ $unwrappedTree && $parser.__updateMaxCursor() || $parser.__registerMismatch() }
      else unwrappedTree
    }
  }

  case object ANY extends TerminalOpTree {
    def ruleTraceTerminal = '{ akka.parboiled2.RuleTrace.ANY }

    override def renderInner(wrapped: Boolean): Expr[Boolean] = {
      val unwrappedTree = '{ $parser.cursorChar != EOI && $parser.__advance() }
      if (wrapped) '{ $unwrappedTree && $parser.__updateMaxCursor() || $parser.__registerMismatch() }
      else unwrappedTree
    }
  }

  case class Unknown(syntax: String, tree: String, outerSyntax: String) extends TerminalOpTree {
    override def ruleTraceTerminal: Expr[RuleTrace.Terminal] =
      '{ RuleTrace.Fail(s"unknown rule: $$infoExpr") }

    override protected def renderInner(wrapped: Boolean): Expr[Boolean] =
      '{
        throw new RuntimeException(
          s"unknown rule: [${${ Expr(syntax) }}] '${${ Expr(tree) }}' in [${${ Expr(outerSyntax) }}]"
        )
      }
  }

  def CharRange(lowerTree: Expr[String], upperTree: Expr[String]): CharacterRange =
    (lowerTree.value, upperTree.value) match {
      case (Some(lower), Some(upper)) =>
        if (lower.length != 1) reportError("lower bound must be a single char string", lowerTree)
        if (upper.length != 1) reportError("upper bound must be a single char string", upperTree)
        val lowerBoundChar = lower.charAt(0)
        val upperBoundChar = upper.charAt(0)
        if (lowerBoundChar > upperBoundChar) reportError("lower bound must not be > upper bound", lowerTree)
        CharacterRange(Expr(lowerBoundChar), Expr(upperBoundChar))
      case _ => reportError("Character ranges must be specified with string literals", lowerTree)
    }

  case class CharacterRange(lowerBound: Expr[Char], upperBound: Expr[Char]) extends TerminalOpTree {
    def ruleTraceTerminal = '{ akka.parboiled2.RuleTrace.CharRange($lowerBound, $upperBound) }

    override def renderInner(wrapped: Boolean): Expr[Boolean] = {
      val unwrappedTree = '{
        val char = $parser.cursorChar
        $lowerBound <= char && char <= $upperBound && $parser.__advance()
      }
      if (wrapped) '{ $unwrappedTree && $parser.__updateMaxCursor() || $parser.__registerMismatch() }
      else unwrappedTree
    }
  }

  case class Fail(stringExpr: Expr[String]) extends OpTree {
    def render(wrapped: Boolean): Expr[Boolean] = '{ throw new akka.parboiled2.Parser.Fail($stringExpr) }
  }
  case class Named(op: OpTree, stringExpr: Expr[String]) extends DefaultNonTerminalOpTree {
    def ruleTraceNonTerminalKey                                        = '{ RuleTrace.Named($stringExpr) }
    def renderInner(start: Expr[Int], wrapped: Boolean): Expr[Boolean] = op.render(wrapped)
  }
  case class Atomic(op: OpTree) extends DefaultNonTerminalOpTree {
    def ruleTraceNonTerminalKey = '{ RuleTrace.Atomic }

    def renderInner(start: Expr[Int], wrapped: Boolean): Expr[Boolean] =
      if (wrapped) '{
        val saved   = $parser.__enterAtomic($start)
        val matched = ${ op.render(wrapped) }
        $parser.__exitAtomic(saved)
        matched
      }
      else op.render(wrapped)
  }

  case class Quiet(op: OpTree) extends DefaultNonTerminalOpTree {
    def ruleTraceNonTerminalKey = '{ RuleTrace.Quiet }

    def renderInner(start: Expr[Int], wrapped: Boolean): Expr[Boolean] =
      if (wrapped) '{
        val saved   = $parser.__enterQuiet()
        val matched = ${ op.render(wrapped) }
        $parser.__exitQuiet(saved)
        matched
      }
      else op.render(wrapped)
  }

  def topLevel(opTree: OpTree, name: Expr[String]): OpTree = RuleCall(Left(opTree), name)

  def deconstruct(outerRule: Expr[Rule[_, _]]): OpTree = deconstructPF(outerRule).get
  def deconstructPF(outerRule: Expr[Rule[_, _]]): Option[OpTree] = {
    import quotes.reflect.*

    def collector(lifter: Term): Collector =
      lifter match {
        case TypeApply(Ident("forRule0" | "forReduction"), _) => rule0Collector
        case TypeApply(Ident("forRule1"), _)                  => rule1Collector
        case x                                                => reportError(s"Unexpected lifter ${lifter.show(using Printer.TreeStructure)}", lifter.asExpr)
      }

    // a list of names for operations that are not yet implemented but that should not be interpreted as rule calls
    // FIXME: can be removed when everything is implemented
    val ruleNameBlacklist =
      Set(
        "str",
        "!",
        "?",
        "&",
        "optional",
        "run",
        "zeroOrMore",
        "times",
        "oneOrMore",
        "rule2ActionOperator",
        "range2NTimes",
        "rule2WithSeparatedBy"
      )

    lazy val rules0PF: PartialFunction[Expr[Rule[_, _]], OpTree] = {
      case '{ ($p: Parser).ch($c) }                                                        => CharMatch(c)
      case '{ ($p: Parser).str($s) }                                                       => StringMatch(s)
      case '{ ($p: Parser).valueMap($m: Map[String, Any]) }                                => MapMatch(m, '{ false })
      case '{ ($p: Parser).valueMap($m: Map[String, Any], $ic) }                           => MapMatch(m, ic)
      case '{ ($p: Parser).ignoreCase($c: Char) }                                          => IgnoreCaseChar(c)
      case '{ ($p: Parser).ignoreCase($s: String) }                                        => IgnoreCaseString(s)
      case '{ ($p: Parser).predicate($pr) }                                                => CharPredicateMatch(pr)
      case '{ ($p: Parser).anyOf($s) }                                                     => AnyOf(s)
      case '{ ($p: Parser).noneOf($s) }                                                    => NoneOf(s)
      case '{ ($p: Parser).ANY }                                                           => ANY
      case '{ ($p: Parser).str2CharRangeSupport($l).-($r) }                                => CharRange(l, r)
      case '{ ($p: Parser).test($flag) }                                                   => SemanticPredicate(flag)
      case '{ ($p: Parser).push[t]($value) }                                               => PushAction(value, Type.of[t])
      case '{ ($p: Parser).drop[t] }                                                       => DropAction(Type.of[t])
      case '{ type i <: HList; type o <: HList; ($p: Parser).capture[`i`, `o`]($arg)($l) } => Capture(rec(arg.asTerm))
      case '{ type i <: HList; type o <: HList; ($p: Parser).runSubParser[`i`, `o`]($f) }  => RunSubParser(f)
      case '{
            type i1 <: HList; type o1 <: HList
            type i2 <: HList; type o2 <: HList
            ($lhs: Rule[`i1`, `o1`]).~[`i2`, `o2`]($rhs)($_, $_)
          } =>
        Sequence(Seq(rec(lhs.asTerm), rec(rhs.asTerm)))

      case '{
            type i1 <: HList; type o1 <: HList
            type i2 <: `i1`; type o2 >: `o1` <: HList
            ($lhs: Rule[`i1`, `o1`]).|[`i2`, `o2`]($rhs)
          } =>
        FirstOf(rec(lhs.asTerm), rec(rhs.asTerm))

      case '{
            type i1 <: HList; type o1 <: HList
            type i2 <: HList; type o2 <: HList
            ($lhs: Rule[`i1`, `o1`]).~!~[`i2`, `o2`]($rhs)($_, $_)
          } =>
        Cut(rec(lhs.asTerm), rec(rhs.asTerm))

      case '{ type i <: HList; type o <: HList; ($p: Parser).zeroOrMore[`i`, `o`]($arg)($l) } =>
        ZeroOrMore(rec(arg.asTerm), collector(l.asTerm))

      case '{ type i <: HList; type o <: HList; ($base: Rule[`i`, `o`]).*($l: support.Lifter[Seq, `i`, `o`]) } =>
        ZeroOrMore(rec(base.asTerm), collector(l.asTerm))

      case '{
            type i <: HList; type o <: HList; ($base: Rule[`i`, `o`]).*($sep: Rule0)($l: support.Lifter[Seq, `i`, `o`])
          } =>
        ZeroOrMore(rec(base.asTerm), collector(l.asTerm), Separator(rec(sep.asTerm)))

      case '{ type i <: HList; type o <: HList; ($p: Parser).oneOrMore[`i`, `o`]($arg)($l) } =>
        OneOrMore(rec(arg.asTerm), collector(l.asTerm))

      case '{ type i <: HList; type o <: HList; ($base: Rule[`i`, `o`]).+($l: support.Lifter[Seq, `i`, `o`]) } =>
        OneOrMore(rec(base.asTerm), collector(l.asTerm))

      case '{
            type i <: HList; type o <: HList; ($base: Rule[`i`, `o`]).+($sep: Rule0)($l: support.Lifter[Seq, `i`, `o`])
          } =>
        OneOrMore(rec(base.asTerm), collector(l.asTerm), Separator(rec(sep.asTerm)))

      case '{ type i <: HList; type o <: HList; ($p: Parser).optional[`i`, `o`]($arg)($l) } =>
        Optional(rec(arg.asTerm), collector(l.asTerm))

      case '{ type i <: HList; type o <: HList; ($base: Rule[`i`, `o`]).?($l: support.Lifter[Option, `i`, `o`]) } =>
        Optional(rec(base.asTerm), collector(l.asTerm))

      case '{ type i <: HList; type o <: HList; ($p: Parser).int2NTimes($n).times[`i`, `o`]($arg)($l) } =>
        Int2NTimes(
          n,
          rec(arg.asTerm),
          MinMaxSupplier.constant(n),
          collector(l.asTerm),
          null
        )
      case '{ type i <: HList; type o <: HList; ($p: Parser).range2NTimes($r).times[`i`, `o`]($arg)($l) } =>
        Range2NTimes(
          r,
          rec(arg.asTerm),
          MinMaxSupplier.range(r),
          collector(l.asTerm),
          null
        )

      case '{ type i <: HList; type o <: HList; !($arg: Rule[`i`, `o`]) } =>
        NotPredicate(rec(arg.asTerm))

      case '{ ($p: Parser).&($arg) } =>
        AndPredicate(rec(arg.asTerm))

      case '{
            type i <: HList; type o <: HList; ($p: Parser).rule2WithSeparatedBy[`i`, `o`]($base).separatedBy($sep)
          } =>
        rec(base.asTerm) match {
          case ws: WithSeparator => ws.withSeparator(Separator(rec(sep.asTerm)))
          case _                 => reportError(s"Illegal `separatedBy` base: $base", base)
        }

      case '{ ($p: Parser).run[t]($e)($l) }                                           => RunAction(e)
      case '{ type i <: HList; type o <: HList; ($base: Rule[`i`, `o`]).named($str) } => Named(rec(base.asTerm), str)
      case '{ type i <: HList; type o <: HList; ($p: Parser).atomic[`i`, `o`]($r) }   => Atomic(rec(r.asTerm))
      case '{ type i <: HList; type o <: HList; ($p: Parser).quiet[`i`, `o`]($r) }    => Quiet(rec(r.asTerm))
      case '{ ($p: Parser).fail($str) }                                               => Fail(str)
      case '{ type i <: HList; type o <: HList; ($p: Parser).failX[`i`, `o`]($str) }  => Fail(str)
    }

    lazy val rules1PF: PartialFunction[Term, OpTree] = {
      // cannot easily be converted because we would have to list all ActionOps instances
      case Apply(
            Apply(
              TypeApply(
                Select(
                  Select(Apply(Apply(TypeApply(Select(_, "rule2ActionOperator"), _), List(base)), _), "~>"),
                  "apply"
                ),
                _
              ),
              List(body)
            ),
            List(_, TypeApply(Ident("apply"), ts))
          ) =>
        Sequence(rec(base), Action(body, ts))

      case call @ (Apply(_, _) | Select(_, _) | Ident(_) | TypeApply(_, _))
          if !callName(call).exists(ruleNameBlacklist) =>
        RuleCall(
          Right(call.asExprOf[Rule[_, _]]),
          Expr(callName(call) getOrElse reportError("Illegal rule call: " + call, call.asExpr))
        )
      //case _ => Unknown(rule.show, rule.show(using Printer.TreeStructure), outerRule.toString)
    }
    lazy val allRules = rules0PF.orElse(rules1PF.compose[Expr[Rule[_, _]]] { case x => x.asTerm.underlyingArgument })
    def rec(rule: Term): OpTree = allRules.applyOrElse(
      rule.asExprOf[Rule[_, _]],
      rule => Unknown(rule.show, "" /*rule.show(using Printer.TreeStructure)*/, outerRule.toString)
    )

    allRules.lift(outerRule)
  }

  private def reportError(error: String, expr: Expr[Any]): Nothing = {
    quotes.reflect.report.error(error, expr)
    throw new scala.quoted.runtime.StopMacroExpansion
  }

  /////////////////////////////////// helpers ////////////////////////////////////

  trait Collector {
    def withCollector(f: CollectorInstance => Expr[Boolean]): Expr[Boolean]
  }
  trait CollectorInstance {
    def popToBuilder: Expr[Unit]
    def pushBuilderResult: Expr[Unit]
    def pushSomePop: Expr[Unit]
    def pushNone: Expr[Unit]
  }

  // no-op collector
  object rule0Collector extends Collector with CollectorInstance {
    override def withCollector(f: CollectorInstance => Expr[Boolean]): Expr[Boolean] = f(this)
    private val unit: Expr[Unit]                                                     = '{}
    def popToBuilder: Expr[Unit]                                                     = unit
    def pushBuilderResult: Expr[Unit]                                                = unit
    def pushSomePop: Expr[Unit]                                                      = unit
    def pushNone: Expr[Unit]                                                         = unit
  }

  object rule1Collector extends Collector {
    override def withCollector(f: CollectorInstance => Expr[Boolean]): Expr[Boolean] = '{
      val builder = new scala.collection.immutable.VectorBuilder[Any]
      ${
        f(new CollectorInstance {
          def popToBuilder: Expr[Unit]      = '{ builder += $parser.valueStack.pop() }
          def pushBuilderResult: Expr[Unit] = '{ $parser.valueStack.push(builder.result()) }
          def pushSomePop: Expr[Unit]       = '{ $parser.valueStack.push(Some($parser.valueStack.pop())) }
          def pushNone: Expr[Unit]          = '{ $parser.valueStack.push(None) }
        })
      }
    }
  }

  type Separator = Boolean => Expr[Boolean]
  private def Separator(op: OpTree): Separator = wrapped => op.render(wrapped)

  @tailrec
  private def callName(tree: Term): Option[String] =
    tree match {
      case Ident(name)       => Some(name)
      case Select(_, name)   => Some(name)
      case Apply(fun, _)     => callName(fun)
      case TypeApply(fun, _) => callName(fun)
      case _                 => None
    }

  // tries to match and expand the leaves of the given Tree
  private def expand(expr: Expr[_], wrapped: Boolean): Expr[Boolean] =
    expand(expr.asTerm, wrapped).asExprOf[Boolean]
  private def expand(tree: Tree, wrapped: Boolean): Tree =
    tree match {
      case Block(statements, res) => block(statements, expand(res, wrapped).asInstanceOf[Term])
      case If(cond, thenExp, elseExp) =>
        If(cond, expand(thenExp, wrapped).asInstanceOf[Term], expand(elseExp, wrapped).asInstanceOf[Term])
      case Match(selector, cases)    => Match(selector, cases.map(expand(_, wrapped).asInstanceOf[CaseDef]))
      case CaseDef(pat, guard, body) => CaseDef(pat, guard, expand(body, wrapped).asInstanceOf[Term])
      case x =>
        deconstructPF(x.asExprOf[Rule[_, _]])                  // can we pass the body as a rule?
          .map(_.render(wrapped))                              // then render it
          .getOrElse('{ ${ x.asExprOf[Rule[_, _]] } ne null }) // otherwise, assume the expression is a rule itself
          .asTerm
    }

  private def block(a: Term, b: Term): Term =
    a match {
      case Block(a1, a2) =>
        b match {
          case Block(b1, b2) => Block(a1 ::: a2 :: b1, b2)
          case _             => Block(a1 ::: a2 :: Nil, b)
        }
      case _ =>
        b match {
          case Block(b1, b2) => Block(a :: b1, b2)
          case _             => Block(a :: Nil, b)
        }
    }

  private def block(stmts: List[Statement], expr: Term): Term =
    expr match {
      case Block(a, b) => block(stmts ::: a ::: Nil, b)
      case _           => Block(stmts, expr)
    }
}
