package akka.http.ccompat

import scala.annotation.StaticAnnotation
import scala.language.experimental.macros
import scala.reflect.macros.blackbox.Context

object pre213macro {
  def impl(c: Context)(annottees: c.Expr[Any]*): c.Expr[Any] = annottees match {
    case Seq(method) ⇒
      import c.universe._
      if (scala.util.Properties.versionNumberString.startsWith("2.13"))
        c.Expr[Nothing](Literal(Constant(())))
      else
        method
    case _ ⇒
      throw new IllegalArgumentException("Please annotate single expressions")
  }
}
class pre213 extends StaticAnnotation {
  def macroTransform(annottees: Any*): Any = macro pre213macro.impl
}
