package akka.http.sbt

import sbt._
import Keys._

object Pre213Preprocessor extends AutoPlugin {
  val pre213Files = settingKey[Seq[String]]("files that should be edited for pre213 annotation")

  val pattern = """(?s)@pre213.*?@since213""".r

  override def projectSettings: Seq[Def.Setting[_]] = {
    Compile / sources := {
      if (scalaVersion.value startsWith "3") {
        val filter: File => Boolean = f => pre213Files.value.exists(suffix => f.getAbsolutePath.replace("\\", "//").endsWith(suffix))
        (Compile / sources).value.map { s =>
          if (filter(s)) {
            val data = IO.read(s)
            val targetFile = sourceManaged.value / s.getName
            val newData = pattern.replaceAllIn(data, "@since213")
            IO.write(targetFile, newData)
            targetFile
          } else s
        }
      } else (Compile / sources).value
    }
  }
}