/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import sbt._
import sbt.Keys._

object FuzzySwitch extends AutoPlugin {

  override def trigger: PluginTrigger = allRequirements

  override val projectSettings = Seq(
    commands += fuzzySwitch
  )

  // So we can `sbt "+~ 3 clean compile"`
  //
  // The advantage over `++` is twofold:
  // * `++` also requires the patch version, `+~` finds the first supported Scala version that matches the prefix (if any)
  // * When subprojects need to be excluded, ++ needs to be specified for each command
  //
  // So the `++` equivalent of the above example is `sbt "++ 3.1.1-RC1 clean" "++ 3.1.1-RC1 compile"`
  val fuzzySwitch: Command = Command.args("+~", "<version> <args>")({ (initialState: State, args: Seq[String]) =>
    {
      val requestedVersionPrefix = args.head
      val requestedVersion = Dependencies.allScalaVersions.filter(_.startsWith(requestedVersionPrefix)).head

      def run(state: State, command: String): State = {
        val parsed = s"++ $requestedVersion $command".foldLeft(Cross.switchVersion.parser(state))((p, i) => p.derive(i))
        parsed.resultEmpty match {
          case e: sbt.internal.util.complete.Parser.Failure =>
            throw new IllegalStateException(e.errors.mkString(", "))
          case sbt.internal.util.complete.Parser.Value(v) =>
            v()
        }
      }
      val commands = args.tail
      commands.foldLeft(initialState)(run)
    }
  })

}
