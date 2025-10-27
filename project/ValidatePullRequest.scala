/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import com.github.sbt.pullrequestvalidator.ValidatePullRequest
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
    validatePullRequest / includeFilter := ValidatePullRequest.PathGlobFilter("akka-http-*/**"),
    validatePullRequestBuildAll / excludeFilter := ValidatePullRequest.PathGlobFilter("project/MiMa.scala"),
    prValidatorGithubRepository := Some("akka/akka-core"),
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
  override def trigger = allRequirements
  override def requires = AkkaHttpValidatePullRequest && MimaPlugin
  override lazy val projectSettings =
    AkkaHttpValidatePullRequest.CliOptions.mimaEnabled.ifTrue(
      AkkaHttpValidatePullRequest.additionalTasks += mimaReportBinaryIssues).toList
}

/**
 * This autoplugin adds Paradox doc generation to validatePullRequest task,
 * when a project has ParadoxPlugin autoplugin enabled.
 */
object ParadoxWithPrValidation extends AutoPlugin {
  override def trigger = allRequirements
  override def requires = AkkaHttpValidatePullRequest && ParadoxPlugin
  override lazy val projectSettings = Seq(AkkaHttpValidatePullRequest.additionalTasks += Compile / paradox)
}

object UnidocWithPrValidation extends AutoPlugin {
  override def trigger = noTrigger
  override lazy val projectSettings = Seq(AkkaHttpValidatePullRequest.additionalTasks += Compile / unidoc)
}
