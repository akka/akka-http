/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package fix

import scalafix.v1._
import scala.meta._

class BindToServerBuilderApi extends SemanticRule("BindToServerBuilderApi") {

  override def fix(implicit doc: SemanticDocument): Patch =
    doc.tree.collect {
      case t @ q"Http().bindAndHandleAsync(..$params)" =>
        val args = Seq("handler", "interface", "port", "connectionContext", "settings", "parallelism", "log")
        val argExps = namedArgMap(args, params)

        // FIXME: warn about parallelism if it exists

        Patch.replaceTree(t, s"${builder(argExps)}.bind(${argExps("handler")})")

      case t @ q"Http().bindAndHandle(..$params)" =>

        val args = Seq("handler", "interface", "port", "connectionContext", "settings", "log")
        val argExps = namedArgMap(args, params)

        val handler = argExps("handler")
        handler.synthetics match {
          case ApplyTree(fun, _) :: Nil if fun.symbol.exists(_.displayName == "routeToFlow") /* FIXME: could use real symbol instead */ =>
            // migrate bindAndHandle(route) to bindAndHandleAsync(route)
            Patch.replaceTree(t, s"${builder(argExps)}.bind($handler)")
          case _ =>
            Patch.replaceTree(t, s"${builder(argExps)}.bindFlow($handler)")
        }

      case t @ q"Http().bindAndHandleSync(..$params)" =>
        val args = Seq("handler", "interface", "port", "connectionContext", "settings", "log")
        val argExps = namedArgMap(args, params)

        Patch.replaceTree(t, s"${builder(argExps)}.bindSync(${argExps("handler")})")

      case t @ q"Http().bind(..$params)" =>
        val args = Seq("interface", "port", "connectionContext", "settings", "log")
        val argExps = namedArgMap(args, params)

        Patch.replaceTree(t, s"${builder(argExps)}.connectionSource()")

    }.asPatch

  def builder(argExps: Map[String, Term]): String = {
    def clause(name: String, exp: String => String): String =
      if (argExps.contains(name)) s".${exp(argExps(name).toString)}"
      else ""

    val extraClauses =
      clause("connectionContext", e => s"enableHttps($e)") + // FIXME: should be dropped if e is HttpConnectionContext
        clause("settings", e => s"withSettings($e)") +
        clause("log", e => s"logTo($e)")

    s"Http().newServerAt(${argExps("interface")}, ${argExps.getOrElse("port", 0)})$extraClauses"
  }
  def namedArgMap(names: Seq[String], exps: Seq[Term]): Map[String, Term] = {
    val idx = exps.lastIndexWhere(!_.isInstanceOf[Term.Assign])
    val positional = exps.take(idx + 1)
    val named = exps.drop(idx + 1)
    (positional.zipWithIndex.map {
      case (expr, idx) => names(idx) -> expr
    } ++
      named.map {
        case q"$name = $expr" => name.asInstanceOf[Term.Name].value -> expr
      }
    ).toMap
  }
}
