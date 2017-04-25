/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl

import java.util.concurrent.atomic.AtomicInteger

import akka.{ Done, NotUsed }
import akka.actor.ActorRef
import akka.event.Logging
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives
import akka.http.scaladsl.settings.ConnectionPoolSettings
import akka.stream._
import akka.stream.scaladsl.{ Flow, Keep, Sink, Source }
import akka.stream.stage.GraphStageLogic.StageActor
import akka.stream.stage._
import akka.testkit.{ AkkaSpec, ImplicitSender }
import akka.util.ByteString
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.duration._
import scala.concurrent.{ Await, Future, Promise }
import scala.util.Try

/**
 * Spec for https://github.com/akka/akka-http/issues/983
 */
class OutgoingConnectionCancellationShouldKillEntityStreamSpec extends AkkaSpec with ScalaFutures
  with Directives with RequestBuilding with ImplicitSender {

  implicit val mat = ActorMaterializer()

  private val connCounter = new AtomicInteger()
  def conn = connCounter.incrementAndGet()
  val routes = get {
    val c = conn
    val verySlowSource = Source.repeat(ByteString("hello " * 1000))
      .log(s"server-out-$c", _.utf8String.take(20))
      .withAttributes(ActorAttributes.logLevels(Logging.WarningLevel, Logging.WarningLevel, Logging.WarningLevel))
      .throttle(1, per = 1.second, 1, ThrottleMode.shaping)
      .take(30).alsoTo(Sink.onComplete { done ⇒ println("DONE!") })
    complete(HttpResponse(entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, verySlowSource)))
  }

  val (_, host, port) = TestUtils.temporaryServerHostnameAndPort()
  var binding: Future[Http.ServerBinding] = _
  override protected def atStartup(): Unit =
    binding = Http().bindAndHandle(routes, host, port)

  override protected def beforeTermination(): Unit =
    if (binding ne null) binding.futureValue.unbind().futureValue

  "canceling outgoing connection" should {
    "tear down the connection" in {

      val controlPromise = Promise[ActorRef]

      val request = HttpRequest(uri = s"http://$host:$port/${connCounter.get}")

      val flow: Flow[HttpResponse, HttpRequest, NotUsed] =
        Flow.fromGraph(new GraphStage[FlowShape[HttpResponse, HttpRequest]] {
          private val in = Inlet[HttpResponse]("in")
          private val out = Outlet[HttpRequest]("out")
          override val shape = FlowShape[HttpResponse, HttpRequest](in, out)

          override protected def initialAttributes: Attributes = Attributes.name("MYSTAGE")

          override def createLogic(inheritedAttributes: Attributes) =
            new GraphStageLogic(shape) with InHandler with OutHandler with StageLogging {

              @volatile private var replyTo: ActorRef = _ // volatile since we access it from other stream

              private lazy val self = getStageActor {
                case (sender, "cancel") ⇒
                  replyTo = sender
                  cancel(in)
              }

              override def preStart = {
                controlPromise.success(self.ref)
              }

              override def onPush(): Unit = {
                val response = grab(in)
                response.entity.dataBytes
                  .via(KillSwitches.single)
                  .withAttributes(ActorAttributes.logLevels(Logging.WarningLevel, Logging.WarningLevel, Logging.WarningLevel))
                  .alsoTo(Sink.onComplete { s ⇒ replyTo ! s })
                  .runWith(Sink.ignore)
              }

              override def onPull(): Unit = {
                push(out, request)
                pull(in)
              }

              setHandlers(in, out, this)
            }
        })
          // .log("user-flow")
          .withAttributes(ActorAttributes.logLevels(Logging.WarningLevel, Logging.WarningLevel, Logging.WarningLevel))

      val connectionFlow = Http().outgoingConnection(host, port)
      Await.ready(connectionFlow.join(flow).run(), 3.seconds)

      Thread.sleep(1000) // give it a moment to start streaming the entity
      val control = Await.result(controlPromise.future, 3.seconds)
      within(1.second) {
        // the server pushes data for many seconds, 
        // so if we get a completion here after canceling it means the cancel has worked
        control ! "cancel"
        val done = expectMsgType[Try[Done]]
        done.failed.get.getMessage should include("Connection stream cancelled while still reading incoming entity")
      }
    }

    // FIXME should the server see the connection as closed at this point?
  }

}

