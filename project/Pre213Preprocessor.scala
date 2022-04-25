package akka.http.sbt

import sbt._
import Keys._

object Pre213Preprocessor extends AutoPlugin {
  val pre213Files = settingKey[Seq[String]]("files that should be edited for pre213 annotation")

  val pattern = """(?s)@pre213.*?@since213""".r

  override def projectSettings: Seq[Def.Setting[_]] = {
    Compile / sources := {
      if (scalaVersion.value startsWith "3") {
        val filter = pre213Files.value.toSet
        (Compile / sources).value.map { s =>
          if (filter(s.getName)) {
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

object ReplaceApp extends App {
  val pattern = """(?s)@pre213.*?@since213""".r
  val f = file("/home/johannes/git/opensource/akka-http/akka-http-core/src/main/scala/akka/http/scaladsl/model/headers/headers.scala")
  val data = IO.read(f)
  val targetFile = file("/tmp/test")
  val newData = pattern.replaceAllIn(data, "@since213")
  IO.write(targetFile, newData)
}