/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import sbt._
import Keys._

object Common extends AutoPlugin {
  override def trigger: PluginTrigger = allRequirements
  override lazy val projectSettings = Seq(
    scalacOptions ++= Seq(
      "-deprecation",
      "-encoding", "UTF-8", // yes, this is 2 args
      "-target:jvm-1.8",
      "-unchecked",
      "-Xlint",
      // "-Yno-adapted-args", //akka-http heavily depends on adapted args and => Unit implicits break otherwise
      "-Ywarn-dead-code"
      // "-Xfuture" // breaks => Unit implicits
    ),
    // '-release' parameter is restricted to 'Compile, compile' scope because
    // otherwise `sbt akka-http-xml/compile:doc` fails with it on Scala 2.12.9
    scalacOptions in (Compile, compile) ++= {
      if (scalaMinorVersion.value >= 12 && !sys.props("java.version").startsWith("1.") /* i.e. Java version >= 9 */)
        Seq("-release", "8")
      else
        Nil
    },
    javacOptions ++= Seq(
      "-encoding", "UTF-8",
    ),
    javacOptions ++= {
      if (!sys.props("java.version").startsWith("1.") /* i.e. Java version >= 9 */)
        Seq("--release", "8")
      else
        Seq("-source", "1.8")
    },
    // restrict to 'compile' scope because otherwise it is also passed to
    // javadoc and -target is not valid there.
    // https://github.com/sbt/sbt/issues/1785
    javacOptions in (Compile, compile) ++= {
      if (!sys.props("java.version").startsWith("1.") /* i.e. Java version >= 9 */)
        Nil
      else
        Seq("-target", "1.8")
    },
  )

  def scalaMinorVersion: Def.Initialize[Long] = Def.setting { CrossVersion.partialVersion(scalaVersion.value).get._2 }

}
