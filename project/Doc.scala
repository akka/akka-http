/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import sbt._
import sbt.Keys._
import scala.annotation.tailrec
import sbtunidoc.{ GenJavadocPlugin, JavaUnidocPlugin, ScalaUnidocPlugin }
import sbtunidoc.BaseUnidocPlugin.autoImport.{ unidoc, unidocProjectFilter }
import sbtunidoc.JavaUnidocPlugin.autoImport.JavaUnidoc
import sbtunidoc.ScalaUnidocPlugin.autoImport.ScalaUnidoc
import sbtunidoc.GenJavadocPlugin.autoImport.{ Genjavadoc, unidocGenjavadocVersion }
import Common.isJdk8

object Doc {
  val BinVer = """(\d+\.\d+)\.\d+""".r
}

object Scaladoc extends AutoPlugin {

  object CliOptions {
    val scaladocDiagramsEnabled = CliOption("akka.scaladoc.diagrams", true)
    val scaladocAutoAPI = CliOption("akka.scaladoc.autoapi", true)
  }

  override def trigger = allRequirements
  override def requires = plugins.JvmPlugin

  val validateDiagrams = settingKey[Boolean]("Validate generated scaladoc diagrams")

  override lazy val projectSettings =
    inTask(doc)(Seq(
      Compile / scalacOptions ++=
        scaladocOptions(
          scalaBinaryVersion.value,
          version.value,
          isSnapshot.value,
          (ThisBuild / baseDirectory).value,
          libraryDependencies.value
            .filter(_.configurations.contains("plugin->default(compile)"))
            // Can we get the from the classpath somehow?
            .map(module => file(s"~/.ivy2/cache/${module.organization}/${module.name}_${scalaVersion.value}/jars/${module.name}_${scalaVersion.value}-${module.revision}.jar"))
        ),
      autoAPIMappings := CliOptions.scaladocAutoAPI.get
    )) ++
    Seq(Compile / validateDiagrams := true) ++
    CliOptions.scaladocDiagramsEnabled.ifTrue(Compile / doc := {
      val docs = (Compile / doc).value
      if ((Compile / validateDiagrams).value)
        scaladocVerifier(docs)
      docs
    })

  def scaladocOptions(scalaBinaryVersion: String, ver: String, isSnapshot: Boolean, base: File, plugins: Seq[File]): List[String] = {
    val urlString = GitHub.url(ver, isSnapshot) + "€{FILE_PATH_EXT}#L€{FILE_LINE}"

    val opts = List(
      "-implicits",
      "-groups",
      "-doc-source-url", urlString,
      "-sourcepath", base.getAbsolutePath,
      "-doc-title", "Akka HTTP",
      "-doc-version", ver,
      "-doc-canonical-base-url", "https://doc.akka.io/api/akka-http/current/"
    ) ++
      plugins.map(plugin => "-Xplugin:" + plugin) ++
      // Workaround https://issues.scala-lang.org/browse/SI-10028
      (if (scalaBinaryVersion == "3")
          // https://github.com/lampepfl/dotty/issues/14939
          List("-skip-packages:akka.pattern:org.specs2")
       else
          List("-skip-packages", "akka.pattern:org.specs2"))
    CliOptions.scaladocDiagramsEnabled.ifTrue("-diagrams").toList ::: opts
  }

  def scaladocVerifier(file: File): File = {
    @tailrec
    def findHTMLFileWithDiagram(dirs: Seq[File]): Boolean = {
      if (dirs.isEmpty) false
      else {
        val curr = dirs.head
        val (newDirs, files) = curr.listFiles.partition(_.isDirectory)
        val rest = dirs.tail ++ newDirs
        val hasDiagram = files exists { f =>
          val name = f.getName
          if (name.endsWith(".html") && !name.startsWith("index-") &&
              !name.equals("index.html") && !name.equals("package.html")) {
            val source = scala.io.Source.fromFile(f)(scala.io.Codec.UTF8)
            val hd = try source.getLines().exists(lines =>
              lines.contains("<div class=\"toggleContainer block diagram-container\" id=\"inheritance-diagram-container\">") ||
              lines.contains("<svg id=\"graph")
            )
            catch {
              case e: Exception => throw new IllegalStateException("Scaladoc verification failed for file '"+f+"'", e)
            } finally source.close()
            hd
          }
          else false
        }
        hasDiagram || findHTMLFileWithDiagram(rest)
      }
    }

    // if we have generated scaladoc and none of the files have a diagram then fail
    if (file.exists() && !findHTMLFileWithDiagram(List(file)))
      sys.error("ScalaDoc diagrams not generated!")
    else
      file
  }
}

/**
 * For projects with few (one) classes there might not be any diagrams.
 */
object ScaladocNoVerificationOfDiagrams extends AutoPlugin {

  override def trigger = noTrigger
  override def requires = Scaladoc

  override lazy val projectSettings = Seq(
    Compile / Scaladoc.validateDiagrams := false
  )
}

/**
 * Unidoc settings for root project. Adds unidoc command.
 */
object UnidocRoot extends AutoPlugin {

  object autoImport {
    val unidocProjectExcludes = settingKey[Seq[ProjectReference]]("Excluded unidoc projects")
  }
  import autoImport._

  object CliOptions {
    val genjavadocEnabled = CliOption("akka.genjavadoc.enabled", false)
  }

  override def trigger = noTrigger
  override def requires = ScalaUnidocPlugin && CliOptions.genjavadocEnabled.ifTrue(JavaUnidocPlugin).getOrElse(plugins.JvmPlugin)

  val akkaSettings = UnidocRoot.CliOptions.genjavadocEnabled.ifTrue(Seq(
    JavaUnidoc / unidoc / javacOptions ++= (
      if (isJdk8) Seq("-Xdoclint:none")
      else Seq("-Xdoclint:none", "--ignore-source-errors")),
    // genjavadoc needs to generate synthetic methods since the java code uses them
    // fails since 10.0.11 disabled to get the doc gen to pass, see #1584
    // scalacOptions += "-P:genjavadoc:suppressSynthetic=false",
    // FIXME: see https://github.com/akka/akka-http/issues/230
    JavaUnidoc / unidoc / sources ~= (_.filterNot(_.getPath.contains("Access$minusControl$minusAllow$minusOrigin")))
  )).getOrElse(Nil)

  val settings = inTask(unidoc)(Seq(
    ScalaUnidoc / unidocProjectFilter := inAnyProject -- inProjects(unidocProjectExcludes.value: _*),
    JavaUnidoc / unidocProjectFilter := inAnyProject -- inProjects(unidocProjectExcludes.value: _*)
  ))

  override lazy val projectSettings =
    settings ++
    akkaSettings
}

/**
 * Unidoc settings for every multi-project. Adds genjavadoc specific settings.
 */
object BootstrapGenjavadoc extends AutoPlugin {

  override def trigger = allRequirements
  override def requires = UnidocRoot.CliOptions.genjavadocEnabled.ifTrue(GenJavadocPlugin).getOrElse(plugins.JvmPlugin)

  override lazy val projectSettings = UnidocRoot.CliOptions.genjavadocEnabled.ifTrue(
    Seq(
      compile / javacOptions += "-Xdoclint:none",
      test / javacOptions += "-Xdoclint:none",
      doc / javacOptions += "-Xdoclint:none",
      Compile / scalacOptions += "-P:genjavadoc:fabricateParams=true",
      unidocGenjavadocVersion in Global := "0.18"
    )
  ).getOrElse(Seq.empty)
}
