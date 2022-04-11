/*
 * Copyright (C) 2017-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import sbt._
import Keys._

import scala.util.matching.Regex.Groups

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
          case Some("master") => masterSnapshot
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

  // Default version updated only when needed, https://doc.akka.io//docs/akka/current/project/downstream-upgrade-strategy.html
  val minimumExpectedAkkaVersion = "2.6.18"
  val default = akkaDependency(defaultVersion = minimumExpectedAkkaVersion)
  val minimumExpectedAkka26Version = "2.6.18"
  val docs = akkaDependency(defaultVersion = minimumExpectedAkka26Version)

  lazy val masterSnapshot = Artifact(determineLatestSnapshot(), true)

  val akkaVersion: String = default match {
    case Artifact(version, _) => version
    case Sources(uri, _) => uri
  }

  implicit class RichProject(project: Project) {
    /** Adds either a source or a binary dependency, depending on whether the above settings are set */
    def addAkkaModuleDependency(module: String,
                                config: String = "",
                                akka: Akka = default): Project =
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
            libraryDependencies += {
              if (config == "")
                "com.typesafe.akka" %% module % akkaVersion
              else
                "com.typesafe.akka" %% module % akkaVersion % config
            },
            resolvers ++= (if (akkaSnapshot) Seq("Sonatype Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots") else Nil)
          )
      }
  }

  private def determineLatestSnapshot(prefix: String = ""): String = {
    import sbt.librarymanagement.Http.http
    import gigahorse.GigahorseSupport.url
    import scala.concurrent.Await
    import scala.concurrent.duration._

    val snapshotVersionR = """href=".*/((\d+)\.(\d+)\.(\d+)\+(\d+)-[0-9a-f]+-SNAPSHOT)/"""".r

    // akka-cluster-sharding-typed_2.13 seems to be the last nightly published by `akka-publish-nightly` so if that's there then it's likely the rest also made it
    val body = Await.result(http.run(url("https://oss.sonatype.org/content/repositories/snapshots/com/typesafe/akka/akka-cluster-sharding-typed_2.13/")), 10.seconds).bodyAsString
    val allVersions =
      snapshotVersionR.findAllMatchIn(body)
        .map {
          case Groups(full, ep, maj, min, offset) =>
            (
              ep.toInt,
              maj.toInt,
              min.toInt,
              offset.toInt
            ) -> full
        }
        .filter(_._2.startsWith(prefix))
        .toVector.sortBy(_._1)
    allVersions.last._2
  }
}
