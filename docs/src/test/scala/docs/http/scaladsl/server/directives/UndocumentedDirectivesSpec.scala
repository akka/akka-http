/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.scaladsl.server.directives

import java.io.File
import org.scalatest.WordSpec
import scala.meta._

class UndocumentedDirectivesSpec extends WordSpec {
  val SourceDir = new File("akka-http/src/main/scala/akka/http/scaladsl/server/directives")
  val ParadoxDir = new File("docs/src/main/paradox/scala/http/routing-dsl/directives")

  SourceDir.listFiles.foreach { file =>
    val traitName = file.getName.replace(".scala", "")
    val dirName = traitName.replaceAll("(.)([A-Z])", "$1-$2").toLowerCase

    s"$traitName has all directives documented" in {
      val documented = documentedDirectives(dirName)
      val undocumented = directivesInTrait(traitName).filterNot(documented)

      assert(undocumented.isEmpty)
    }
  }

  def documentedDirectives(dirName: String): Set[String] = {
    val mdFiles = Option(new File(ParadoxDir, dirName).listFiles).getOrElse(Array())
    mdFiles.map(_.getName.replace(".md", "")).filter("index".!=).toSet
  }

  def directivesInTrait(traitName: String): Set[String] = {
    val file = new File(SourceDir, traitName + ".scala")
    val source = file.parse[Source].get

    def fromTrait(tree: Tree): Boolean = {
      val grandParentIsTrait = for {
        parent <- tree.parent if parent.is[Template]
        grandParent <- parent.parent
      } yield grandParent match {
        case Defn.Trait(_, name, _, _, _) => name.value == traitName
        case _                            => false
      }

      grandParentIsTrait.getOrElse(false)
    }

    val directives = source.collect {
      case tree @ q"def $name[..$paramTypes](..$args): $restype = $body" if fromTrait(tree) ⇒
        name.value

      case tree @ q"def $name[..$paramTypes]: $restype = $body" if fromTrait(tree) ⇒
        name.value
    }

    directives.toSet
  }
}
