/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.fix

import scalafix.lint.LintSeverity
import scalafix.v1._

import scala.meta._
import scala.util.Try

class MigrateToServerBuilder extends SemanticRule("MigrateToServerBuilder") {

  override def fix(implicit doc: SemanticDocument): Patch = {
    def patch(t: Tree, http: Term, targetMethod: Term => String): Patch = {
      val args = t.symbol.info.get.signature.asInstanceOf[MethodSignature].parameterLists.head.map(_.displayName)
      val materializerAndTarget: Option[(Tree, Term)] =
        Try {
          val sig = t.parent.get.symbol.info.get.signature.asInstanceOf[MethodSignature]
          require(sig.parameterLists(1)(0).signature.toString == "Materializer")
          (t.parent.get, t.parent.get.asInstanceOf[Term.Apply].args.head)
        }.toOption

      val materializerLint: Option[Patch] = materializerAndTarget.map {
        case (_, matArg) =>
          Patch.lint(Diagnostic(
            "custom-materializer-warning",
            "Custom materializers are often not needed any more. You can often remove custom materializers and " +
              "use the system materializer which is supplied automatically.",
            matArg.pos,
            severity = LintSeverity.Warning
          ))
      }

      val argExps = namedArgMap(args, t.asInstanceOf[Term.Apply].args) ++ materializerAndTarget.map("materializer" -> _._2).toSeq
      val targetTree = materializerAndTarget.map(_._1).getOrElse(t) // patch parent if materializer arg is found

      patchTree(targetTree, http, argExps, targetMethod(argExps("handler"))) + materializerLint
    }

    def patchTree(t: Tree, http: Term, argExps: Map[String, Term], targetMethod: String): Patch =
      Patch.replaceTree(t, s"${builder(http, argExps)}.$targetMethod(${argExps("handler")})")

    def handlerIsRoute(handler: Term): Boolean =
      handler.symbol.info.exists(_.signature.toString contains "Route") || // doesn't seem to work with synthetics on
        (handler.synthetics match { // only works with `scalacOptions += "-P:semanticdb:synthetics:on"`
          case ApplyTree(fun, _) :: Nil => fun.symbol.exists(_.displayName == "routeToFlow") // somewhat inaccurate, but that name should be unique enough for our purposes
          case _                        => false
        })
    def bindAndHandleTargetMethod(handler: Term): String =
      if (handlerIsRoute(handler)) "bind" else "bindFlow"

    def builder(http: Term, argExps: Map[String, Term])(implicit doc: SemanticDocument): String = {
      def clause(name: String, exp: String => String, onlyIf: Term => Boolean = _ => true): String =
        if (argExps.contains(name) && onlyIf(argExps(name))) s".${exp(argExps(name).toString)}"
        else ""

      // This is an approximate test if the parameter might have type `HttpConnectionContext`.
      // Due to limitations of scalafix (https://scalacenter.github.io/scalafix/docs/developers/semantic-type.html#test-for-subtyping)
      // we cannot do accurate type tests against `HttpConnectionContext`. This will suffice for simple expressions,
      // for more complicated ones we will just create an `enableHttps()` clause that will fail to compile if someone
      // has done something weird which is fine for now.
      def isNotHttpConnectionContext(term: Term): Boolean =
        !term.symbol.info.exists(_.signature.toString.contains("HttpConnectionContext"))

      val extraClauses =
        clause("connectionContext", e => s"enableHttps($e)", isNotHttpConnectionContext) +
          clause("settings", e => s"withSettings($e)") +
          clause("log", e => s"logTo($e)") +
          clause("materializer", e => s"withMaterializer($e)")

      s"$http.newServerAt(${argExps("interface")}, ${argExps.getOrElse("port", 0)})$extraClauses"
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
    // still pretty inaccurate but scala meta doesn't support proper type information of terms in public API, so hard to
    // do it better than this
    def isHttpExt(http: Term): Boolean = http match {
      case q"Http()" => true
      case x if x.symbol.info.exists(_.signature.toString contains "HttpExt") => true
      case _ => false
    }

    doc.tree.collect {
      case t @ q"$http.bindAndHandleAsync(..$params)" if isHttpExt(http) =>
        // FIXME: warn about parallelism if it exists

        patch(t, http, _ => "bind")

      case t @ q"$http.bindAndHandle(..$params)" if isHttpExt(http)     => patch(t, http, bindAndHandleTargetMethod)
      case t @ q"$http.bindAndHandleSync(..$params)" if isHttpExt(http) => patch(t, http, _ => "bindSync")
      case t @ q"$http.bind(..$params)" if isHttpExt(http) =>
        val args = Seq("interface", "port", "connectionContext", "settings", "log")
        val argExps = namedArgMap(args, params)

        Patch.replaceTree(t, s"${builder(http, argExps)}.connectionSource()")

    }.asPatch
  }
}
