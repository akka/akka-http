package akka

import sbt._
import java.io.{File, FileNotFoundException}

import com.lightbend.paradox._
import markdown.Writer
import com.lightbend.paradox.sbt.ParadoxPlugin.autoImport._
import com.lightbend.paradox.markdown.{LeafBlockDirective, _}
import org.pegdown.Printer
import org.pegdown.ast.{DirectiveNode, VerbatimNode, Visitor}

import scala.collection.JavaConverters._
import scala.io.Source

object ParadoxSupport {
  val paradoxWithSignatureDirective = Seq(
    paradoxProcessor in Compile ~= { _ =>
      // FIXME: this is a HACK so far that copies stuff over from paradox
      // it would be better if the plugin has a way of specifying extra directives through normal sbt mechanisms
      // see https://github.com/lightbend/paradox/issues/35
      new ParadoxProcessor(writer =
        new Writer(serializerPlugins = context =>
          Seq(
          new ActiveLinkSerializer,
          new AnchorLinkSerializer,
          new DirectiveSerializer(Writer.defaultDirectives(context) :+
            new SignatureDirective(context.location.tree.label)
      ))))
    }
  )

  class SignatureDirective(page: Page) extends LeafBlockDirective("signature") {
    def render(node: DirectiveNode, visitor: Visitor, printer: Printer): Unit =
      try {
        val labels = node.attributes.values("identifier").asScala
        val file = new File(page.file.getParentFile, node.source)

        val Signature = """\s*((?:def|val) (\w+)(?=[:(\[]).*)\s+\=.*""".r // stupid approximation to match a signature
        //println(s"Looking for signature regex '$Signature'")
        val text =
          Source.fromFile(file).getLines.collect {
            case line@Signature(full, l, _*) if labels contains l =>
              //println(s"Found label '$l' with sig '$full' in line $line")
              full
          }.mkString("\n")

        val lang = Option(node.attributes.value("type")).getOrElse(Snippet.language(file))
        new VerbatimNode(text, lang).accept(visitor)
      } catch {
        case e: FileNotFoundException =>
          throw new SnipDirective.LinkException(s"Unknown snippet [${e.getMessage}] referenced from [${page.path}]")
      }
  }
}