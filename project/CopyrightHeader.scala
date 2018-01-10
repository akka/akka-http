package akka

import sbt._, Keys._
import de.heikoseeberger.sbtheader.{ CommentCreator, HeaderPlugin }

object CopyrightHeader extends AutoPlugin {
  import HeaderPlugin.autoImport._
  import ValidatePullRequest.{ additionalTasks, ValidatePR }

  override def requires = HeaderPlugin
  override def trigger = allRequirements

  override def projectSettings = Def.settings(
    Seq(Compile, Test).flatMap { config =>
      inConfig(config)(
        Seq(
          headerLicense := Some(HeaderLicense.Custom(headerFor(CurrentYear))),
          headerMappings := headerMappings.value ++ Map(
            HeaderFileType.scala       -> cStyleComment,
            HeaderFileType.java        -> cStyleComment,
            HeaderFileType("template") -> cStyleComment
          )
        )
      )
    },
    additionalTasks in ValidatePR += headerCheck in Compile,
    additionalTasks in ValidatePR += headerCheck in Test
  )

  val CurrentYear = java.time.Year.now.getValue.toString
  val CopyrightPattern = "(?s).* Copyright \\([Cc]\\) (\\d{4}(-\\d{4})?) (Lightbend|Typesafe) Inc.*".r

  def headerFor(year: String): String =
    s"Copyright (C) $year Lightbend Inc. <https://www.lightbend.com>"

  val cStyleComment = HeaderCommentStyle.cStyleBlockComment.copy(commentCreator = new CommentCreator() {
    import HeaderCommentStyle.cStyleBlockComment.commentCreator

    def updateLightbendHeader(header: String): String = header match {
      case CopyrightPattern(years, null, _)     => commentCreator(headerFor(years))
      case CopyrightPattern(years, endYears, _) => commentCreator(headerFor(years.replace(endYears, "-" + CurrentYear)))
      case _                                    => header.trim
    }

    override def apply(text: String, existingText: Option[String]): String = {
      existingText
        .map(updateLightbendHeader)
        .getOrElse(commentCreator(text, existingText))
    }
  })
}
