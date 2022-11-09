package akka

import sbt.{Def, _}
import Keys._

object NoScala3 extends AutoPlugin {
  override def projectSettings: Seq[Def.Setting[_]] = Seq(
    crossScalaVersions := crossScalaVersions.value.filterNot(_.startsWith("3.")),
  )
}
