/*
 * Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import scala.language.postfixOps

import sbt.{Def, _}
import Keys._
import com.geirsson.CiReleasePlugin

/**
 * For projects that are not published.
 */
object NoPublish extends AutoPlugin {
  override def requires = plugins.JvmPlugin

  override def projectSettings = Seq(
    publish / skip := true,
    publishArtifact := false,
    publish := {},
    publishLocal := {},
  )
}

object Publish extends AutoPlugin {
  override def requires = plugins.JvmPlugin
  override def trigger = AllRequirements

  // Import the GPG key from `Global / onLoad`, not a task dependency: task-graph based
  // hooks into publishSigned are fragile, since other plugins (eg. sbt-pgp itself) can
  // redefine that key later in the settings merge and silently drop the hook.
  override def globalSettings: Seq[Def.Setting[_]] = Seq(
    Global / onLoad := (Global / onLoad).value.andThen { state =>
      if (sys.env.contains("PGP_SECRET")) {
        CiReleasePlugin.setupGpg()
        val snapshot = Project.extract(state).get(ThisBuild / isSnapshot)
        if (!snapshot)
          cloudsmithCredentials(validate = true)
      }
      state
    }
  )

  override def projectSettings: Seq[Def.Setting[_]] = Seq(
    publishTo :=
      (if (isSnapshot.value)
        Some("Cloudsmith API".at("https://maven.cloudsmith.io/lightbend/akka-snapshots/"))
      else
        Some("Cloudsmith API".at("https://maven.cloudsmith.io/lightbend/akka/"))),
      credentials ++= cloudsmithCredentials(validate = false)
  )

  def cloudsmithCredentials(validate: Boolean): Seq[Credentials] = {
    (sys.env.get("PUBLISH_USER"), sys.env.get("PUBLISH_PASSWORD")) match {
      case (Some(user), Some(password)) =>
        Seq(Credentials("Cloudsmith API", "maven.cloudsmith.io", user, password))
      case _ =>
        if (validate)
          throw new Exception("Publishing credentials expected in `PUBLISH_USER` and `PUBLISH_PASSWORD`.")
        else
          Nil
    }
  }
}
