package akka

import java.util.concurrent.atomic.AtomicReference

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Route
import akka.stream.{OverflowStrategy, SharedKillSwitch}
import akka.stream.scaladsl.{BroadcastHub, Flow, Keep, Sink, Source}
import com.lightbend.paradox.sbt.ParadoxPlugin.autoImport.paradox
import com.typesafe.config.ConfigFactory
import sbt.{Def, _}
import sbt.Keys._

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.Try

object ParadoxBrowser extends AutoPlugin {
  override def trigger = allRequirements
  override def requires = AkkaParadoxPlugin

  val paradoxServe = taskKey[Unit]("Browse paradox docs")

  trait ServerInterface {
    def reload(): Unit
    def shutdown(): Future[Done]
    def port(): Int
  }
  object EmptyInterface extends ServerInterface {
    override def reload(): Unit = ()
    override def shutdown(): Future[Done] = Future.successful(Done)
    override def port(): Int = 0
  }
  val server = new AtomicReference[ServerInterface](EmptyInterface)

  override def projectSettings: Seq[Def.Setting[_]] = Seq(
    paradoxServe := browseDocs((sources in paradox in Compile).value, (target in paradox in Compile).value),
    paradox in Compile := {
      val p = (paradox in Compile).value
      server.get().reload()
      p
    }
  )

  def browseDocs(sources: Seq[File], baseDir: File): Unit = {
    val port = server.get().port()
    Await.result(server.get().shutdown(), 5.seconds)
    println(s"Browsing at $baseDir")

    val config = ConfigFactory.defaultApplication(classOf[ActorSystem].getClassLoader)
    implicit val system = ActorSystem("sbt-paradox", config, classLoader = classOf[ActorSystem].getClassLoader)
    import system.dispatcher
    import akka.http.scaladsl.server.Directives._

    val (queue, hub) =
      Source.queue[Unit](1, OverflowStrategy.dropBuffer)
        .toMat(BroadcastHub.sink[Unit])(Keep.both)
        .run()

    val autoReloader: Route = {
      val txtSource =
        Source.single("Waiting for termination...") ++
          hub.take(1)
            .map(_ => "Now terminating")

      val sink =
        Sink.foreach[Message] {
          case TextMessage.Strict(path) =>
            val path0 = path.replaceAll("""\.html$""", ".md")
            val allSources = sources.**("*.md").get()
            val found = Try(allSources.filter(_.getAbsolutePath.endsWith(path0)).minBy(_.length)).toOption
            println(s"Trying to find source for $path at $found")
            found.foreach { p =>
              val cmd = s"""/home/johannes/bin/run-idea.sh "${p.getAbsolutePath}""""
              println(s"Running cmd [$cmd]")
              sys.process.stringToProcess(cmd).!
            }
          case x => throw new IllegalStateException(x.toString)
        }

      val flow =
        Flow.fromSinkAndSourceCoupled[Message, Message](sink, txtSource.map(TextMessage(_)))
      handleWebSocketMessages(flow)
    }


    val route =
      concat(
        pathSingleSlash(getFromFile(baseDir / "index.html")),
        path("ws-watchdog")(autoReloader),
        getFromDirectory(baseDir.getAbsolutePath)
      )

    val bindingF =
      Http()
        .newServerAt("127.0.0.1", port)
        .adaptSettings(_.mapTimeouts(_.withIdleTimeout(Duration.Inf)))
        .bind(route)
    val binding = Await.result(bindingF, 5.seconds)
    val local = binding.localAddress
    println(s"Browse at http://127.0.0.1:${local.getPort}/")

    server.set(new ServerInterface {
      override def port(): Int = local.getPort
      override def reload(): Unit = queue.offer(())
      override def shutdown(): Future[Done] = system.terminate().map(_ => Done)
    })
  }
}
