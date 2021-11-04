/*
 * Copyright (C) 2017-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import sbt._
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys.MultiJvm
import com.typesafe.sbt.SbtScalariform.ScalariformKeys

object Formatting {
  import scalariform.formatter.preferences._

  lazy val formatSettings = Seq(
    ScalariformKeys.preferences := setPreferences(ScalariformKeys.preferences.value),
    Compile / ScalariformKeys.preferences := setPreferences(ScalariformKeys.preferences.value),
    MultiJvm / ScalariformKeys.preferences := setPreferences(ScalariformKeys.preferences.value)
  )

  lazy val docFormatSettings = Seq(
    ScalariformKeys.preferences := setPreferences(ScalariformKeys.preferences.value),
    Compile / ScalariformKeys.preferences := setPreferences(ScalariformKeys.preferences.value),
    Test / ScalariformKeys.preferences := setPreferences(ScalariformKeys.preferences.value),
    MultiJvm / ScalariformKeys.preferences := setPreferences(ScalariformKeys.preferences.value)
  )

  def setPreferences(preferences: IFormattingPreferences) = preferences
    .setPreference(RewriteArrowSymbols, true)
    .setPreference(UseUnicodeArrows, false)
    .setPreference(AlignParameters, true)
    .setPreference(AlignSingleLineCaseStatements, true)
    .setPreference(DoubleIndentConstructorArguments, false)
    .setPreference(DoubleIndentMethodDeclaration, false)
    .setPreference(DanglingCloseParenthesis, Preserve)
    .setPreference(NewlineAtEndOfFile, true)
}
