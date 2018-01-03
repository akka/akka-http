package akka

import sbt._, Keys._
import de.heikoseeberger.sbtheader.HeaderPlugin

object CopyrightHeader extends AutoPlugin {
  import HeaderPlugin.autoImport.{ HeaderLicense, headerCheck, headerCreate, headerLicense }
  import ValidatePullRequest.{ additionalTasks, ValidatePR }

  override def requires = HeaderPlugin
  override def trigger = allRequirements

  override def projectSettings = Def.settings(
    Seq(Compile, Test).flatMap { config =>
      inConfig(config)(
        Seq(
          includeFilter in headerCreate := (includeFilter in unmanagedSources).value,
          excludeFilter in headerCreate := (excludeFilter in unmanagedSources).value,
          unmanagedSources in headerCreate := Defaults.collectFiles(
            unmanagedSourceDirectories,
            includeFilter in headerCreate,
            excludeFilter in headerCreate
          ).value,
          unmanagedSources in headerCheck := Defaults.collectFiles(
            unmanagedSourceDirectories,
            includeFilter in headerCreate,
            excludeFilter in headerCreate
          ).value,
          headerLicense := Some(HeaderLicense.Custom(
            """|Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
               |""".stripMargin
          ))
        )
      )
    },
    additionalTasks in ValidatePR += headerCheck in Compile,
    additionalTasks in ValidatePR += headerCheck in Test
  )
}
