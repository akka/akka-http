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
          case Some("main") | Some("master") => mainSnapshot
          case Some("snapshot") => Artifact(determineLatestSnapshot(), true)
          case Some("default") => Artifact(defaultVersion)
          case Some(other) => Artifact(other, true)
          case None => Artifact(defaultVersion)
        }
    }
  }

  // Default version updated only when needed, https://doc.akka.io//docs/akka/current/project/downstream-upgrade-strategy.html
  val minimumExpectedAkkaVersion = "2.9.2"
  val default = akkaDependency(defaultVersion = minimumExpectedAkkaVersion)
  val docs = akkaDependency(defaultVersion = minimumExpectedAkkaVersion)

  lazy val mainSnapshot = Artifact(determineLatestSnapshot(), true)

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
            resolvers += "Akka library repository".at("https://repo.akka.io/maven"),
            resolvers ++= (if (akkaSnapshot) Seq("Akka library snapshot repository".at("https://repo.akka.io/snapshots")) else Nil)
          )
      }
  }

  private def determineLatestSnapshot(): String = {
    import sbt.librarymanagement.Http.http
    import gigahorse.GigahorseSupport.url
    import scala.concurrent.Await
    import scala.concurrent.duration._

    val versionFile = "https://doc.akka.io/docs/akka/snapshot/paradox.json"
    val body = Await.result(http.run(url(versionFile)), 10.seconds).bodyAsString
    val versionProperty = """"version" : """"
    val i = body.indexOf(versionProperty)
    if (i == -1)
      throw new RuntimeException(s"No version in $versionFile")
    body.substring(i).split('"')(3)
  }
}
