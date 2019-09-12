/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import java.io.FileOutputStream
import java.nio.charset.Charset

import com.typesafe.tools.mima.core
import sbt._
import sbt.Keys._
import com.typesafe.tools.mima.core.ProblemFilter
import com.typesafe.tools.mima.plugin.MimaKeys.mimaBackwardIssueFilters
import com.typesafe.tools.mima.plugin.MimaKeys.mimaBinaryIssueFilters
import com.typesafe.tools.mima.plugin.MimaKeys.mimaCheckDirection
import com.typesafe.tools.mima.plugin.MimaKeys.mimaCurrentClassfiles
import com.typesafe.tools.mima.plugin.MimaKeys.mimaForwardIssueFilters
import com.typesafe.tools.mima.plugin.MimaKeys.mimaPreviousClassfiles
import com.typesafe.tools.mima.plugin.MimaPlugin
import com.typesafe.tools.mima.plugin.MimaPlugin.autoImport._
import com.typesafe.tools.mima.plugin.SbtLogger
import com.typesafe.tools.mima.plugin.SbtMima

import scala.util.Try

object MiMa extends AutoPlugin {
  val mimaCreateExclusionTemplate = taskKey[Seq[File]]("Creates templates for missing exclusions")

  override def requires = MimaPlugin
  override def trigger = allRequirements

  //Exclude these non-existent versions when checking compatibility with previous versions
  private val ignoredModules = Map(
    "akka-http-caching" -> Set("10.0.0", "10.0.1", "10.0.2", "10.0.3", "10.0.4", "10.0.5", "10.0.6", "10.0.7", "10.0.8", "10.0.9", "10.0.10")
  )

  // A fork is a branch of the project where new releases are created that are not ancestors of the current release line
  val forks = Seq("10.0.")
  val currentFork = "10.1."

  override val projectSettings = Seq(
    mimaPreviousArtifacts := {
      // manually maintained list of previous versions to make sure all incompatibilities are found
      // even if so far no files have been been created in this project's mima-filters directory
      val pre213Versions = Set(
          "10.0.0",
          "10.0.1",
          "10.0.2",
          "10.0.3",
          "10.0.4",
          "10.0.5",
          "10.0.6",
          "10.0.7",
          "10.0.8",
          "10.0.9",
          "10.0.10",
          "10.0.11",
          "10.0.12",
          "10.0.13",
          "10.0.14",
          "10.0.15",
          "10.1.0",
          "10.1.1",
          "10.1.2",
          "10.1.3",
          "10.1.4",
          "10.1.5",
          "10.1.6",
          "10.1.7",
      )
      val post213Versions = Set(
          "10.1.8",
          "10.1.9",
      )

      val versions =
        if (scalaVersion.value == Dependencies.Scala213) post213Versions
        else pre213Versions ++ post213Versions

      versions.collect { case version if !ignoredModules.get(name.value).exists(_.contains(version)) =>
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
    },
    mimaCreateExclusionTemplate := {
      val log = streams.value.log
      val highestVersion = mimaPreviousArtifacts.value.toSeq.maxBy(_.revision)(versionOrdering).revision
      val prefix = sys.props.getOrElse("akka.http.mima.exclusion-template-prefix", "_generated")

      val allExcludes =
        mimaPreviousClassfiles.value.toSeq.flatMap {
        case (module, file) =>
          val (backward, forward) = SbtMima.runMima(
            file,
            mimaCurrentClassfiles.value,
            (fullClasspath in mimaFindBinaryIssues).value,
            mimaCheckDirection.value,
            new SbtLogger(streams.value)
          )

          val filters = mimaBinaryIssueFilters.value
          val backwardFilters = mimaBackwardIssueFilters.value
          val forwardFilters = mimaForwardIssueFilters.value

          def isReported(module: ModuleID, verionedFilters: Map[String, Seq[core.ProblemFilter]])(problem: core.Problem) = (verionedFilters.collect {
            // get all filters that apply to given module version or any version after it
            case f @ (version, filters) if versionOrdering.gteq(version, module.revision) => filters
          }.flatten ++ filters).forall { f => f(problem) }

          val backErrors = backward filter isReported(module, backwardFilters)
          val forwErrors = forward filter isReported(module, forwardFilters)

          val filteredCount = backward.size + forward.size - backErrors.size - forwErrors.size
          val filteredNote = if (filteredCount > 0) " (filtered " + filteredCount + ")" else ""

          if (backErrors.size + forwErrors.size > 0) backErrors.flatMap(p => p.howToFilter.map((_, module.revision)))
          else Nil
      }

      if (allExcludes.nonEmpty) {
        val lines: List[String] =
          "# Autogenerated MiMa filters, please pull branch and check validity, and add comments why they are needed etc." ::
          "# Don't merge like this." :: "" ::
          allExcludes.groupBy(_._1).mapValues(_.map(_._2).toSet).groupBy(_._2).flatMap {
            case (versionsAffected, exclusions) =>
              val versions = versionsAffected.toSeq.sorted(versionOrdering)
              s"# Incompatibilities against ${versions.mkString(", ")}" +: exclusions.keys.toVector.sorted :+ ""
          }.toList

        val targetFile = new File(mimaFiltersDirectory.value, s"$highestVersion.backwards.excludes/$prefix.excludes")
        targetFile.getParentFile.mkdirs()
        IO.writeLines(targetFile, lines, utf8, false)
        log.info(s"Created ${targetFile.getPath} as a template to ignore pending mima issues. Remove `.template` suffix to activate.")
        targetFile :: Nil
      } else
        Nil
    }
  )

  def latestForkVersion(fork: String, allVersions: Seq[String]): String =
    allVersions
      .filter(_.startsWith(fork))
      .sorted(versionOrdering)
      .last

  // copied from https://github.com/lightbend/migration-manager/blob/e54f3914741b7f528da5507e515cc84db60abdd5/core/src/main/scala/com/typesafe/tools/mima/core/ProblemReporting.scala#L14-L19
  private val versionOrdering = Ordering[(Int, Int, Int)].on { version: String =>
    val ModuleVersion = """(\d+)\.?(\d+)?\.?(.*)?""".r
    val ModuleVersion(epoch, major, minor) = version
    val toNumeric = (revision: String) => Try(revision.replace("x", Short.MaxValue.toString).filter(_.isDigit).toInt).getOrElse(0)
    (toNumeric(epoch), toNumeric(major), toNumeric(minor))
  }

  private val utf8 = Charset.forName("utf-8")
}
