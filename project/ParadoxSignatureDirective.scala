package akka

import java.io.{File, FileNotFoundException}

import sbt._
import Keys._
import com.lightbend.paradox._
import com.lightbend.paradox.markdown._
import com.lightbend.paradox.sbt.ParadoxPlugin.autoImport._
import org.pegdown.Printer
import org.pegdown.ast.{DirectiveNode, HtmlBlockNode, VerbatimNode, Visitor}

import scala.collection.JavaConverters._
import scala.io.{Codec, Source}

import _root_.io.github.lukehutch.fastclasspathscanner.FastClasspathScanner
import _root_.io.github.lukehutch.fastclasspathscanner.scanner.ScanResult


object ParadoxSupport {
  val paradoxWithSignatureDirective = Seq(
    paradoxDirectives ++= Def.taskDyn {
      val log = streams.value.log
      val classpath = (fullClasspath in Compile).value.files.map(_.toURI.toURL).toArray
      val classloader = new java.net.URLClassLoader(classpath, this.getClass().getClassLoader())
      val scanner = new FastClasspathScanner().addClassLoader(classloader).scan()
      val directives = paradoxDirectives.value
      Def.task { Seq(
        { context: Writer.Context ⇒
            new SignatureDirective(context.location.tree.label, context.properties, msg ⇒ log.warn(msg))
        },
        { context: Writer.Context ⇒ {
            val scaladocDirective = directives.map(_.apply(context)).collect { case x: ScaladocDirective => x }.head
            val javadocDirective = directives.map(_.apply(context)).collect { case x: JavadocDirective => x }.head
            new UnidocDirective(scaladocDirective, javadocDirective, scanner)
          }
        },
      )}
    }.value
  )

  class UnidocDirective(scaladocDirective: ScaladocDirective, javadocDirective: JavadocDirective, scanner: ScanResult) extends InlineDirective("unidoc") {
    def render(node: DirectiveNode, visitor: Visitor, printer: Printer): Unit = {
      val matches = scanner.getNamesOfAllStandardClasses.asScala.filter(_.startsWith("akka.http")).filter(_.endsWith('.' + node.label))
      if (matches.size > 2)
        throw new java.lang.IllegalStateException(s"Multiple matches found for ${node.label}: " + matches.mkString(", "))
      matches.foreach(c => {
        val syntheticSource = new DirectiveNode.Source.Direct(c)
        def syntheticNode(group: String) = {
          val attributes = new org.pegdown.ast.DirectiveAttributes.AttributeMap()
          new DirectiveNode(DirectiveNode.Format.Inline, group, null, null, attributes, null,
            new DirectiveNode(DirectiveNode.Format.Inline, group + "doc", node.label, syntheticSource, node.attributes, c, node.contentsNode)
          )
        }

        if (!c.contains("javadsl"))
          syntheticNode("scala").accept(visitor)
        if (!c.contains("scaladsl"))
          syntheticNode("java").accept(visitor)
      })
    }
  }

  class SignatureDirective(page: Page, variables: Map[String, String], logWarn: String => Unit) extends LeafBlockDirective("signature") {
    def render(node: DirectiveNode, visitor: Visitor, printer: Printer): Unit =
      try {
        val labels = node.attributes.values("identifier").asScala.map(_.toLowerCase())
        val source = node.source match {
          case direct: DirectiveNode.Source.Direct => direct.value
          case _                                   => sys.error("Source references are not supported")
        }
        val file =
          if (source startsWith "$") {
            val baseKey = source.drop(1).takeWhile(_ != '$')
            val base = new File(PropertyUrl(s"signature.$baseKey.base_dir", variables.get).base.trim)
            val effectiveBase = if (base.isAbsolute) base else new File(page.file.getParentFile, base.toString)
            new File(effectiveBase, source.drop(baseKey.length + 2))
          } else new File(page.file.getParentFile, source)

        val Signature = """\s*((def|val|type) (\w+)(?=[:(\[]).*)(\s+\=.*)""".r // stupid approximation to match a signature
        //println(s"Looking for signature regex '$Signature'")
        val text =
          Source.fromFile(file)(Codec.UTF8).getLines.collect {
            case line@Signature(signature, kind, l, definition) if labels contains l.toLowerCase() =>
              //println(s"Found label '$l' with sig '$full' in line $line")
              if (kind == "type") signature + definition
              else signature
          }.mkString("\n")

        if (text.trim.isEmpty) {
          logWarn(
            s"Did not find any signatures with one of those names [${labels.mkString(", ")}] in ${node.source} " +
            s"(was referenced from [${page.path}])")

          new HtmlBlockNode(s"""<div style="color: red;">[Broken signature inclusion [${labels.mkString(", ")}] to [${node.source}]</div>""").accept(visitor)
        } else {
          val lang = Option(node.attributes.value("type")).getOrElse(Snippet.language(file))
          new VerbatimNode(text, lang).accept(visitor)
        }
      } catch {
        case e: FileNotFoundException =>
          throw new SnipDirective.LinkException(s"Unknown snippet [${e.getMessage}] referenced from [${page.path}]")
      }
  }
}
