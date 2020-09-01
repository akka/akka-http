/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import sbt._
import sbt.Keys._
import scala.language.implicitConversions

object Dependencies {
  import DependencyHelpers._

  val jacksonVersion = "2.10.5"
  val junitVersion = "4.12"
  val h2specVersion = "1.5.0"
  val h2specName = s"h2spec_${DependencyHelpers.osName}_amd64"
  val h2specExe = "h2spec" + DependencyHelpers.exeIfWindows
  val h2specUrl = s"https://github.com/summerwind/h2spec/releases/download/v${h2specVersion}/${h2specName}.zip"
  val silencerVersion = "1.7.1"

  val scalaTestVersion = "3.1.4"
  val specs2Version = "4.10.3"
  val scalaCheckVersion = "1.14.3"

  val scalafixVersion = _root_.scalafix.sbt.BuildInfo.scalafixVersion // grab from plugin

  val scala212Version = "2.12.12"
  val scala213Version = "2.13.3"

  val Versions = Seq(
    crossScalaVersions := Seq(scala213Version, scala212Version),
    scalaVersion := crossScalaVersions.value.head,
  )

  object Provided {
    val jsr305 = "com.google.code.findbugs" % "jsr305" % "3.0.2" % "provided" // ApacheV2

    val scalaReflect  = ScalaVersionDependentModuleID.versioned("org.scala-lang" % "scala-reflect" % _ % "provided") // Scala License
  }

  object Compile {
    val scalaXml      = "org.scala-lang.modules"      %% "scala-xml"                   % "1.3.0" // Scala License

    // For akka-http spray-json support
    val sprayJson   = "io.spray"                     %% "spray-json"                   % "1.3.5"       // ApacheV2

    // For akka-http-jackson support
    val jacksonDatabind = "com.fasterxml.jackson.core" % "jackson-databind"            % jacksonVersion // ApacheV2

    // For akka-http-testkit-java
    val junit       = "junit"                         % "junit"                        % junitVersion  // Common Public License 1.0

    val hpack       = "com.twitter"                   % "hpack"                        % "1.0.2"       // ApacheV2

    val caffeine    = "com.github.ben-manes.caffeine" % "caffeine"                     % "2.8.5"

    val scalafix    = "ch.epfl.scala"                 %% "scalafix-core"               % Dependencies.scalafixVersion // grab from plugin

    object Docs {
      val sprayJson   = Compile.sprayJson                                                                    % "test"
      val gson        = "com.google.code.gson"             % "gson"                    % "2.8.6"             % "test"
      val jacksonXml  = "com.fasterxml.jackson.dataformat" % "jackson-dataformat-xml"  % jacksonVersion      % "test" // ApacheV2
      val reflections = "org.reflections"                  % "reflections"             % "0.9.12"            % "test" // WTFPL
    }

    object Test {
      val sprayJson    = Compile.sprayJson                                         % "test" // ApacheV2
      val junit        = Compile.junit                                             % "test" // Common Public License 1.0
      val specs2       = "org.specs2"     %% "specs2-core"     % specs2Version     % "test" // MIT
      val scalacheck   = "org.scalacheck" %% "scalacheck"      % scalaCheckVersion % "test" // New BSD
      val junitIntf    = "com.novocode"    % "junit-interface" % "0.11"            % "test" // MIT

      val scalatest               = "org.scalatest"     %% "scalatest"       % scalaTestVersion          % "test" // ApacheV2
      val scalatestplusScalacheck = "org.scalatestplus" %% "scalacheck-1-14" % (scalaTestVersion + ".0") % "test"
      val scalatestplusJUnit      = "org.scalatestplus" %% "junit-4-12"      % (scalaTestVersion + ".0") % "test"

      // HTTP/2
      val h2spec       = "io.github.summerwind"        % h2specName                     % h2specVersion      % "test" from(h2specUrl) // MIT
    }
  }

  import Compile._

  lazy val l = libraryDependencies

  lazy val parsing = Seq(
    DependencyHelpers.versionDependentDeps(
      Dependencies.Provided.scalaReflect
    ),
  )

  lazy val httpCore = l ++= Seq(
    Test.sprayJson, // for WS Autobahn test metadata
    Test.scalatest, Test.scalatestplusScalacheck, Test.scalatestplusJUnit, Test.junit
  )

  lazy val httpCaching = l ++= Seq(
    caffeine,
    Provided.jsr305,
    Test.scalatest
  )

  lazy val http = Seq()

  lazy val http2 = l ++= Seq(hpack)

  lazy val http2Support = l ++= Seq(Test.h2spec)

  lazy val httpTestkit = l ++= Seq(
    Test.junit, Test.junitIntf, Compile.junit % "provided",
    Test.scalatest.withConfigurations(Some("provided; test")),
    Test.specs2.withConfigurations(Some("provided; test"))
  )

  lazy val httpTests = l ++= Seq(Test.junit, Test.scalatest, Test.junitIntf)

  lazy val httpXml = Seq(
    versionDependentDeps(scalaXml),
    libraryDependencies += Test.scalatest
  )

  lazy val httpSprayJson = Seq(
    versionDependentDeps(sprayJson),
    libraryDependencies += Test.scalatest
  )

  lazy val httpJackson = l ++= Seq(jacksonDatabind, Test.scalatestplusJUnit, Test.junit, Test.junitIntf)

  lazy val docs = l ++= Seq(Docs.sprayJson, Docs.gson, Docs.jacksonXml, Docs.reflections)
}


object DependencyHelpers {
  case class ScalaVersionDependentModuleID(modules: String => Seq[ModuleID]) {
    def %(config: String): ScalaVersionDependentModuleID =
      ScalaVersionDependentModuleID(version => modules(version).map(_ % config))
  }
  object ScalaVersionDependentModuleID {
    implicit def liftConstantModule(mod: ModuleID): ScalaVersionDependentModuleID = versioned(_ => mod)

    def versioned(f: String => ModuleID): ScalaVersionDependentModuleID = ScalaVersionDependentModuleID(v => Seq(f(v)))
    def fromPF(f: PartialFunction[String, ModuleID]): ScalaVersionDependentModuleID =
      ScalaVersionDependentModuleID(version => if (f.isDefinedAt(version)) Seq(f(version)) else Nil)
  }

  /**
   * Use this as a dependency setting if the dependencies contain both static and Scala-version
   * dependent entries.
   */
  def versionDependentDeps(modules: ScalaVersionDependentModuleID*): Def.Setting[Seq[ModuleID]] =
    libraryDependencies ++= scalaVersion(version => modules.flatMap(m => m.modules(version))).value

  val ScalaVersion = """\d\.\d+\.\d+(?:-(?:M|RC)\d+)?""".r
  val nominalScalaVersion: String => String = {
    // matches:
    // 2.12.0-M1
    // 2.12.0-RC1
    // 2.12.0
    case version @ ScalaVersion() => version
    // transforms 2.12.0-custom-version to 2.12.0
    case version => version.takeWhile(_ != '-')
  }

  // OS name for Go binaries
  def osName: String = {
    val os = System.getProperty("os.name").toLowerCase()
    if (os startsWith "mac") "darwin"
    else if (os startsWith "win") "windows"
    else "linux"
  }

  def exeIfWindows: String = {
    val os = System.getProperty("os.name").toLowerCase()
    if (os startsWith "win") ".exe"
    else ""
  }

}
