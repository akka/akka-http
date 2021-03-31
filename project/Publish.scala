/*
 * Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import scala.language.postfixOps
import sbt._, Keys._
import sbtwhitesource.WhiteSourcePlugin.autoImport.whitesourceIgnore

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
