/*
 * Copyright (C) 2017-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import sbt._
import Keys._

object AkkaDependency {

  sealed trait Akka
  case class Artifact(version: String) extends Akka
  case class Sources(uri: String) extends Akka

  def akkaDependency(defaultVersion: String): Akka = {
    Option(System.getProperty("akka.sources")) match {
      case Some(akkaSources) =>
        Sources(akkaSources)
      case None =>
        Option(System.getProperty("akka.http.build.akka.version")) match {
          case Some("master") => Sources("git://github.com/akka/akka.git#master")
          case Some("release-2.5") => Sources("git://github.com/akka/akka.git#release-2.5")
          case Some("default") => Artifact(defaultVersion)
          case Some(other) => Artifact(other)
          case None => Artifact(defaultVersion)
        }
    }
  }
  // Default version updated only when needed, https://doc.akka.io//docs/akka/current/project/downstream-upgrade-strategy.html
  val default = akkaDependency(defaultVersion = "2.5.31")

  val akkaVersion: String = default match {
    case Artifact(version) => version
    case Sources(uri) => uri
  }

  implicit class RichProject(project: Project) {
    /** Adds either a source or a binary dependency, depending on whether the above settings are set */
    def addAkkaModuleDependency(module: String,
                                config: String = "",
                                akka: Akka = default,
                                onlyIf: Boolean = true): Project =
      if (onlyIf) {
        akka match {
          case Sources(sources) =>
            // as a little hacky side effect also disable aggregation of samples
            System.setProperty("akka.build.aggregateSamples", "false")

            val moduleRef = ProjectRef(uri(sources), module)
            val withConfig: ClasspathDependency =
              if (config == "") moduleRef
              else moduleRef % config

            project.dependsOn(withConfig)
          case Artifact(akkaVersion) =>
            project.settings(libraryDependencies += {
              val dep = "com.typesafe.akka" %% module % akkaVersion
                val withConfig =
                if (config == "") dep
                else dep % config
              withConfig
            })
        }
      }
      else project // return unchanged
  }
}
