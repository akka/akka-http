/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

/*
import java.io._

import akka.MimaWithPrValidation.{MimaResult, NoErrors, Problems}
import net.virtualvoid.sbt.graph.ModuleGraph
import net.virtualvoid.sbt.graph.backend.SbtUpdateReport
import org.kohsuke.github.GHIssueComment
import org.kohsuke.github.GitHubBuilder
import sbt.Keys._
import sbt._
import sbt.access.Aggregation
import sbt.access.Aggregation.Complete
import sbt.access.Aggregation.KeyValue
import sbt.access.Aggregation.ShowConfig
import sbt.internal.util.MainAppender
import sbt.internal.Act
import sbt.internal.BuildStructure
import sbt.std.Transform.DummyTaskMap
import sbt.util.LogExchange
import sbtunidoc.BaseUnidocPlugin.autoImport.unidoc

import scala.collection.JavaConverters._
import scala.collection.immutable
import scala.sys.process._
import scala.util.Try
import scala.util.matching.Regex
 */
import com.hpe.sbt.ValidatePullRequest
import com.hpe.sbt.ValidatePullRequest.PathGlobFilter
import com.lightbend.paradox.sbt.ParadoxPlugin
import com.lightbend.paradox.sbt.ParadoxPlugin.autoImport.paradox
import com.typesafe.tools.mima.plugin.MimaKeys.mimaReportBinaryIssues
import com.typesafe.tools.mima.plugin.MimaPlugin
import sbtunidoc.BaseUnidocPlugin.autoImport.unidoc
import sbt.Keys._
import sbt._

object AkkaHttpValidatePullRequest extends AutoPlugin {

  object CliOptions {
    val mimaEnabled = CliOption("akka.mima.enabled", true)
  }

  import ValidatePullRequest.autoImport._

  override def trigger = allRequirements
  override def requires = ValidatePullRequest

  val ValidatePR = config("pr-validation").extend(Test)

  override lazy val projectConfigurations = Seq(ValidatePR)

  val additionalTasks = settingKey[Seq[TaskKey[_]]]("Additional tasks for pull request validation")

  override lazy val globalSettings = Seq(credentials ++= {
    // todo this should probably be supplied properly
    GitHub.envTokenOrThrow.map { token =>
      Credentials("GitHub API", "api.github.com", "", token)
    }
  }, additionalTasks := Seq.empty)

  override lazy val buildSettings = Seq(
    validatePullRequest / includeFilter := PathGlobFilter("akka-http-*/**"),
    validatePullRequestBuildAll / excludeFilter := PathGlobFilter("project/MiMa.scala"),
    prValidatorGithubRepository := Some("akka/akka"),
    prValidatorTargetBranch := "origin/main")

  override lazy val projectSettings = inConfig(ValidatePR)(Defaults.testTasks) ++ Seq(
    ValidatePR / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-l", "performance"),
    ValidatePR / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-l", "long-running"),
    ValidatePR / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-l", "timing"),
    ValidatePR / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-l", "gh-exclude"),
    // make it fork just like regular test running
    ValidatePR / fork := (Test / fork).value,
    ValidatePR / testGrouping := (Test / testGrouping).value,
    ValidatePR / javaOptions := (Test / javaOptions).value,
    prValidatorTasks := Seq(ValidatePR / test) ++ additionalTasks.value,
    prValidatorEnforcedBuildAllTasks := Seq(Test / test) ++ additionalTasks.value)
}


/**
 * This autoplugin adds MiMa binary issue reporting to validatePullRequest task,
 * when a project has MimaPlugin autoplugin enabled.
 */
object MimaWithPrValidation extends AutoPlugin {
  import AkkaHttpValidatePullRequest._

  override def trigger = allRequirements
  override def requires = AkkaHttpValidatePullRequest && MimaPlugin
  override lazy val projectSettings =
    CliOptions.mimaEnabled.ifTrue(additionalTasks += mimaReportBinaryIssues).toList
}

/**
 * This autoplugin adds Paradox doc generation to validatePullRequest task,
 * when a project has ParadoxPlugin autoplugin enabled.
 */
object ParadoxWithPrValidation extends AutoPlugin {
  import AkkaHttpValidatePullRequest._

  override def trigger = allRequirements
  override def requires = AkkaHttpValidatePullRequest && ParadoxPlugin
  override lazy val projectSettings = Seq(additionalTasks += Compile / paradox)
}

object UnidocWithPrValidation extends AutoPlugin {
  import AkkaHttpValidatePullRequest._

  override def trigger = noTrigger
  override lazy val projectSettings = Seq(additionalTasks += Compile / unidoc)
}