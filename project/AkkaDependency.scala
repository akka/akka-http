/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import sbt._
import Keys._

object AkkaDependency {
  // Property semantics:
  // If akka.sources is set, then the given URI will override everything else
  // else if akka version is "master", then a source dependency to git://github.com/akka/akka.git#master will be used
  // else if akka version is "default", then the hard coded default will be used (jenkins doesn't allow empty values for config axis)
  // else if akka.version is anything else, then the given version will be used

  val defaultAkkaVersion = "2.5.23"
  val akkaVersion =
    System.getProperty("akka.build.version", defaultAkkaVersion) match {
      case "default" => defaultAkkaVersion
      case x => x
    }

  // Needs to be a URI like git://github.com/akka/akka.git#master or file:///xyz/akka
  val akkaSourceDependencyUri = {
    val fallback =
      akkaVersion match {
        case "master" => "git://github.com/akka/akka.git#master"
        case "release-2.5" => "git://github.com/akka/akka.git#release-2.5"
        case x => ""
      }

    System.getProperty("akka.sources", fallback)
  }
  val shouldUseSourceDependency = akkaSourceDependencyUri != ""

  val akkaRepository = {
    // as a little hacky side effect also disable aggregation of samples
    System.setProperty("akka.build.aggregateSamples", "false")

    uri(akkaSourceDependencyUri)
  }

  implicit class RichProject(project: Project) {
    /** Adds either a source or a binary dependency, depending on whether the above settings are set */
    def addAkkaModuleDependency(module: String, config: String = ""): Project =
      if (shouldUseSourceDependency) {
        val moduleRef = ProjectRef(akkaRepository, module)
        val withConfig: ClasspathDependency =
          if (config == "") moduleRef
          else moduleRef % config

        project.dependsOn(withConfig)
      } else {
        project.settings(libraryDependencies += {
          val dep = "com.typesafe.akka" %% module % akkaVersion
          val withConfig =
            if (config == "") dep
            else dep % config
          withConfig
        })
      }
  }
}
