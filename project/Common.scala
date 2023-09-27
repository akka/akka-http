/*
 * Copyright (C) 2017-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import sbt._
import Keys._
import com.lightbend.paradox.projectinfo.ParadoxProjectInfoPluginKeys._
import com.typesafe.tools.mima.plugin.MimaKeys._

object Common extends AutoPlugin {
  override def trigger: PluginTrigger = allRequirements
  override lazy val projectSettings = Seq(
    projectInfoVersion := (if (isSnapshot.value) "snapshot" else version.value),
    scalacOptions ++=
      Seq(
        "-deprecation",
        "-encoding", "UTF-8", // yes, this is 2 args
        "-release", "11",
        "-unchecked",
        "-Wconf:msg=is deprecated \\(since 2\\.13\\.:s") ++
      (if (scalaVersion.value.startsWith("2."))
        // Scala 2.x
        Seq("-Ywarn-dead-code",
          // Silence deprecation notices for changes introduced in Scala 2.12
          // Can be removed when we drop support for Scala 2.12:
          "-Wconf:msg=object JavaConverters in package collection is deprecated:s",
        )
      else
        // Scala 3
        Seq(
          "-Wconf:msg=does not suppress any warnings:s" // some are warnings in 2.12
        )),
    scalacOptions ++= onlyOnScala2(Seq("-Xlint")).value,
    javacOptions ++=
      Seq("-encoding", "UTF-8", "-Xlint:deprecation","--release", "11"),
    // restrict to 'compile' scope because otherwise it is also passed to
    // javadoc and -target is not valid there.
    // https://github.com/sbt/sbt/issues/1785

    // in test code we often use destructing assignment, which now produces an exhaustiveness warning
    // when the type is asserted
    Test / compile / scalacOptions ++=
      (if (scalaVersion.value.startsWith("2.")) {
        // Scala 2.x
        Seq(
          "-Wconf:msg=match may not be exhaustive:s",
          "-Wconf:cat=lint-infer-any:s")
      } else {
        // Scala 3
        Seq.empty
      }),

    Compile / doc / javacOptions := javacOptions.value.filterNot(_ == "-Xlint:deprecation"),

    mimaReportSignatureProblems := true,
    Global / parallelExecution := sys.props.getOrElse("akka.http.parallelExecution", "true") != "false"
  )

  val specificationVersion: String = sys.props("java.specification.version")
  def onlyAfterScala212[T](values: Seq[T]): Def.Initialize[Seq[T]] = Def.setting {
    if (scalaMinorVersion.value >= 12) values else Seq.empty[T]
  }
  def onlyOnScala2[T](values: Seq[T]): Def.Initialize[Seq[T]] = Def.setting {
    if (scalaVersion.value.startsWith("3")) Seq.empty[T] else values
  }

  def scalaMinorVersion: Def.Initialize[Long] = Def.setting { CrossVersion.partialVersion(scalaVersion.value).get._2 }

}
