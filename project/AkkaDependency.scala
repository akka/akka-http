/*
 * Copyright (C) 2017-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import sbt._
import Keys._

object AkkaDependency {

  sealed trait Akka {
    def version: String
    // The version to use in api/japi/docs links,
    // so 'x.y', 'x.y.z', 'current' or 'snapshot'
    def link: String
  }
  case class Artifact(version: String, isSnapshot: Boolean = false) extends Akka {
    override def link = VersionNumber(version) match { case VersionNumber(Seq(x, y, _*), _, _) => s"$x.$y" }
  }
  case class Sources(uri: String, link: String = "current") extends Akka {
    def version = link
  }

  def akkaDependency(defaultVersion: String): Akka = {
    Option(System.getProperty("akka.sources")) match {
      case Some(akkaSources) =>
        Sources(akkaSources)
      case None =>
        Option(System.getProperty("akka.http.build.akka.version")) match {
          case Some("master") => latestAkkaSnapshot
          case Some("release-2.5") =>
            // Don't 'downgrade' building even if akka.sources asks for it
            // (typically for the docs that require 2.6)
            if (defaultVersion.startsWith("2.5")) Artifact(determineLatestSnapshot("2.5"), true)
            else Artifact(defaultVersion)
          case Some("default") => Artifact(defaultVersion)
          case Some(other) => Artifact(other, true)
          case None => Artifact(defaultVersion)
        }
    }
  }

  lazy val latestAkkaSnapshot: Akka = Artifact(determineLatestSnapshot(), true)

  // Default version updated only when needed, https://doc.akka.io//docs/akka/current/project/downstream-upgrade-strategy.html
  val minimumExpectedAkkaVersion = "2.5.31"
  val default = akkaDependency(defaultVersion = minimumExpectedAkkaVersion)
  val minimumExpectedAkka26Version = "2.6.4"
  val docs = akkaDependency(defaultVersion = minimumExpectedAkka26Version)

  val akkaVersion: String = default match {
    case Artifact(version, _) => version
    case Sources(uri, _) => uri
  }

  implicit class RichProject(project: Project) {
    /** Adds either a source or a binary dependency, depending on whether the above settings are set */
    def addAkkaModuleDependency(module: String,
                                config: String = "",
                                akka: Akka = default,
                                includeIfScalaVersionMatches: String => Boolean = _ => true,
                                onlyIf: Boolean = true): Project =
      if (onlyIf) {
        akka match {
          case Sources(sources, _) =>
            // as a little hacky side effect also disable aggregation of samples
            System.setProperty("akka.build.aggregateSamples", "false")

            val moduleRef = ProjectRef(uri(sources), module)
            val withConfig: ClasspathDependency =
              if (config == "") moduleRef
              else moduleRef % config

            project.dependsOn(withConfig)
          case Artifact(akkaVersion, akkaSnapshot) =>
            project.settings(
              libraryDependencies ++= (
                if (includeIfScalaVersionMatches(scalaBinaryVersion.value))
                  Seq(
                    if (config == "")
                      "com.typesafe.akka" %% module % akkaVersion
                    else
                      "com.typesafe.akka" %% module % akkaVersion % config
                  )
                  else Nil),
              resolvers ++= (if (akkaSnapshot) Seq("Akka Snapshots" at "https://repo.akka.io/snapshots") else Nil)
            )
        }
      }
      else project // return unchanged
  }

  private def determineLatestSnapshot(prefix: String = ""): String = {
    import sbt.librarymanagement.Http.http
    import gigahorse.GigahorseSupport.url
    import scala.concurrent.Await
    import scala.concurrent.duration._

    // akka-cluster-sharding-typed_2.13 seems to be the last nightly published by `akka-publish-nightly` so if that's there then it's likely the rest also made it
    val body = Await.result(http.run(url("https://repo.akka.io/snapshots/com/typesafe/akka/akka-cluster-sharding-typed_2.13/")), 10.seconds).bodyAsString
    """href="([^?/].*?)/"""".r.findAllMatchIn(body).map(_.group(1)).filter(_.startsWith(prefix)).toList.last
  }
}
