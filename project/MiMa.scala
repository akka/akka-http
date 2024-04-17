/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import sbt._
import sbt.Keys._
import com.typesafe.tools.mima.core.ProblemFilter
import com.typesafe.tools.mima.plugin.MimaPlugin
import com.typesafe.tools.mima.plugin.MimaPlugin.autoImport._

import scala.util.Try

object MiMa extends AutoPlugin {

  override def requires = MimaPlugin
  override def trigger = allRequirements

  // A fork is a branch of the project where new releases are created that are not ancestors of the current release line
  val forks = Seq("10.2.", "10.4.", "10.5.")
  val currentFork = "10.6."

  // manually maintained list of previous versions to make sure all incompatibilities are found
  // even if so far no files have been been created in this project's mima-filters directory
  val `10.2-versions` = Set(
    "10.2.0",
    "10.2.1",
    "10.2.2",
    "10.2.3",
    "10.2.4",
    "10.2.5",
    "10.2.6",
    "10.2.7",
    "10.2.8",
    "10.2.9",
    "10.2.10",
  )

  val `10.4-versions` = Set(
    "10.4.0",
  )

  val `10.5-versions` = Set(
    "10.5.0",
    "10.5.1",
    "10.5.2",
    "10.5.3",
  )

  val `10.6-versions` = Set(
    "10.6.0",
    "10.6.1"
  )

  val pre3Versions = `10.2-versions` ++ `10.4-versions`

  val post3Versions = `10.6-versions`

  lazy val latestVersion = pre3Versions.max(versionOrdering)
  lazy val latest102Version = `10.2-versions`.max(versionOrdering)

  override val projectSettings = Seq(
    mimaPreviousArtifacts := {
      val versions = {
        if (name.value == "akka-http-jwt") Set.empty // FIXME remove this once we have a release of akka-http-jwt
        else if (scalaBinaryVersion.value == "3") post3Versions
        else pre3Versions ++ post3Versions
      }

      versions.map { version =>
        organization.value %% name.value % version
      }
    },
    mimaBackwardIssueFilters := {
      val filters = mimaBackwardIssueFilters.value
      val allVersions = (mimaPreviousArtifacts.value.map(_.revision) ++ filters.keys).toSeq

      /**
       * Collect filters for all versions of a fork and add them as filters for the latest version of the fork.
       * Otherwise, new versions in the fork that are listed above will reintroduce issues that were already filtered
       * out before. We basically rebase this release line on top of the fork from the view of Mima.
       */
      def forkFilter(fork: String): Option[(String, Seq[ProblemFilter])] = {
        val forkVersions = filters.keys.filter(_.startsWith(fork)).toSeq
        val collectedFilterOption = forkVersions.map(filters).reduceOption(_ ++ _)
        collectedFilterOption.map(latestForkVersion(fork, allVersions) -> _)
      }

      forks.flatMap(forkFilter).toMap ++
      filters.filterKeys(_ startsWith currentFork)
    }
  )

  def latestForkVersion(fork: String, allVersions: Seq[String]): String =
    allVersions
      .filter(_.startsWith(fork))
      .sorted(versionOrdering)
      .last

  // copied from https://github.com/lightbend/migration-manager/blob/e54f3914741b7f528da5507e515cc84db60abdd5/core/src/main/scala/com/typesafe/tools/mima/core/ProblemReporting.scala#L14-L19
  private lazy val versionOrdering = Ordering[(Int, Int, Int)].on { version: String =>
    val ModuleVersion = """(\d+)\.?(\d+)?\.?(.*)?""".r
    val ModuleVersion(epoch, major, minor) = version
    val toNumeric = (revision: String) => Try(revision.replace("x", Short.MaxValue.toString).filter(_.isDigit).toInt).getOrElse(0)
    (toNumeric(epoch), toNumeric(major), toNumeric(minor))
  }
}
