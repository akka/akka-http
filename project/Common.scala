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
    scalacOptions in (Compile, compile) ++=
      onlyAfterScala212(onlyAfterJdk8("-release", "8")).value,
    javacOptions ++=
      Seq("-encoding", "UTF-8") ++ onlyOnJdk8("-source", "1.8") ++ onlyAfterJdk8("--release", "8"),
    // restrict to 'compile' scope because otherwise it is also passed to
    // javadoc and -target is not valid there.
    // https://github.com/sbt/sbt/issues/1785
    javacOptions in (Compile, compile) ++=
      // From jdk9 onwards this is covered by the '-release' flag above
      onlyOnJdk8("-target", "1.8"),
    libraryDependencies ++= Seq(
      compilerPlugin("com.github.ghik" % "silencer-plugin" % silencerVersion cross CrossVersion.full),
      "com.github.ghik" % "silencer-lib" % silencerVersion % Provided cross CrossVersion.full
    ),
  )

  val specificationVersion: String = sys.props("java.specification.version")
  def isJdk8: Boolean =
    VersionNumber(specificationVersion).matchesSemVer(SemanticSelector(s"=1.8"))
  def onlyOnJdk8[T](values: T*): Seq[T] = if (isJdk8) values else Seq.empty[T]
  def onlyAfterJdk8[T](values: T*): Seq[T] = if (isJdk8) Seq.empty[T] else values
  def onlyAfterScala212[T](values: Seq[T]): Def.Initialize[Seq[T]] = Def.setting {
    if (scalaMinorVersion.value >= 12) values else Seq.empty[T]
  }

  def scalaMinorVersion: Def.Initialize[Long] = Def.setting { CrossVersion.partialVersion(scalaVersion.value).get._2 }
  val silencerVersion = "1.4.4"

}
