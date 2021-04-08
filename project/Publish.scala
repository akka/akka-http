/*
 * Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import scala.language.postfixOps
import sbt.{Def, _}
import Keys._
import sbtwhitesource.WhiteSourcePlugin.autoImport.whitesourceIgnore
import xerial.sbt.Sonatype.autoImport.sonatypeProfileName

/**
 * For projects that are not published.
 */
object NoPublish extends AutoPlugin {
  override def requires = plugins.JvmPlugin

  override def projectSettings = Seq(
    skip in publish := true,
    publishArtifact := false,
    publish := {},
    publishLocal := {},
    whitesourceIgnore := true,
  )
}

object Publish extends AutoPlugin {
  override def requires = plugins.JvmPlugin
  override def trigger = AllRequirements

  override def projectSettings: Seq[Def.Setting[_]] = Seq(
    sonatypeProfileName := "com.typesafe",
  )
}