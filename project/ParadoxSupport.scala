/*
 * Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import java.io.{File, FileNotFoundException}

import sbt._
import Keys._
import com.lightbend.paradox.markdown._
import com.lightbend.paradox.sbt.ParadoxPlugin.autoImport._
import org.pegdown.Printer
import org.pegdown.ast.{DirectiveNode, HtmlBlockNode, VerbatimNode, Visitor}

import scala.io.{Codec, Source}
import scala.jdk.CollectionConverters._

object ParadoxSupport {
  val paradoxWithCustomDirectives = Seq(
    paradoxDirectives += ((context: Writer.Context) => new SignatureDirective(context.location.tree.label, context.properties, context))
  )

  class SignatureDirective(page: Page, variables: Map[String, String], ctx: Writer.Context) extends LeafBlockDirective("signature") {
    def render(node: DirectiveNode, visitor: Visitor, printer: Printer): Unit =
      try {
        val labels = node.attributes.values("identifier").asScala.map(_.toLowerCase())
        val source = node.source match {
          case direct: DirectiveNode.Source.Direct => direct.value
          case _                                   => sys.error("Source references are not supported")
        }
        val file = SourceDirective.resolveFile("signature", source, page.file, variables)
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
          ctx.error(
            s"Did not find any signatures with one of those names [${labels.mkString(", ")}]", page, node)

          new HtmlBlockNode(s"""<div style="color: red;">[Broken signature inclusion [${labels.mkString(", ")}] to [${node.source}]</div>""").accept(visitor)
        } else {
          val lang = Option(node.attributes.value("type")).getOrElse(Snippet.language(file))
          new VerbatimNode(text, lang).accept(visitor)
        }
      } catch {
        case e: FileNotFoundException =>
          ctx.error(s"Unknown snippet [${e.getMessage}]", node)
      }
  }
}
