package akka

import scala.language.postfixOps
import sbt._, Keys._

/**
 * For projects that are not published.
 */
object NoPublish extends AutoPlugin {
  override def requires = plugins.JvmPlugin

  override def projectSettings = Seq(
    publishArtifact := false,
    publish := {},
    publishLocal := {}
  )

}

object Publish extends AutoPlugin {
  override def trigger = allRequirements

  override def projectSettings = Seq(
    publishTo := Some("akka-bintray-repo" at "https://api.bintray.com/maven/akka/maven/com.typesafe.akka%3Aakka-http_2.11"),
    credentials ++=
      sys.env.get("BINTRAY_USER").zip(sys.env.get("BINTRAY_PASS")).toList.map { case (user, pass) =>
        Credentials("Bintray API Realm", "api.bintray.com", user, pass)
      }
  )
}

object DeployRsync extends AutoPlugin {
  import scala.sys.process._
  import sbt.complete.DefaultParsers._

  override def requires = plugins.JvmPlugin

  trait Keys {
    val deployRsyncArtifact = taskKey[Seq[(File, String)]]("File or directory and a path to deploy to")
    val deployRsync = inputKey[Unit]("Deploy using SCP")
  }

  object autoImport extends Keys
  import autoImport._

  override def projectSettings = Seq(
    deployRsync := {
      val (_, host) = (Space ~ StringBasic).parsed
      deployRsyncArtifact.value.foreach {
        case (from, to) => s"rsync -rvz $from/ $host:$to"!
      }
    }
  )
}
