/*
 * Copyright (C) 2018-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.testkit

import akka.actor.{ ActorRef, ActorRefWithCell, ActorSystem }
import akka.stream.Materializer
import akka.stream.impl._
import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._
import scala.util.control.NoStackTrace

object Utils {

  /** Sets the default-mailbox to the usual [[akka.dispatch.UnboundedMailbox]] instead of [[StreamTestDefaultMailbox]]. */
  val UnboundedMailboxConfig = ConfigFactory.parseString("""akka.actor.default-mailbox.mailbox-type = "akka.dispatch.UnboundedMailbox"""")

  case class TE(message: String) extends RuntimeException(message) with NoStackTrace

  /** Compatibility between 2.4 and 2.5 */
  type ActorMaterializerImpl = {
    def system: ActorSystem
    def supervisor: ActorRef
  }

  def assertAllStagesStopped[T](block: ⇒ T)(implicit materializer: Materializer): T = {
    val impl = materializer.asInstanceOf[ActorMaterializerImpl] // refined type, will never fail
    val probe = TestProbe()(impl.system)
    probe.send(impl.supervisor, StreamSupervisor.StopChildren)
    probe.expectMsg(StreamSupervisor.StoppedChildren)
    val result = block
    probe.within(5.seconds) {
      var children = Set.empty[ActorRef]
      try probe.awaitAssert {
        impl.supervisor.tell(StreamSupervisor.GetChildren, probe.ref)
        children = probe.expectMsgType[StreamSupervisor.Children].children
        assert(
          children.isEmpty,
          s"expected no StreamSupervisor children, but got [${children.mkString(", ")}]")
      }
      catch {
        case ex: Throwable ⇒
          children.foreach(_ ! StreamSupervisor.PrintDebugDump)
          throw ex
      }
    }
    result
  }

  def assertDispatcher(ref: ActorRef, dispatcher: String): Unit = ref match {
    case r: ActorRefWithCell ⇒
      if (r.underlying.props.dispatcher != dispatcher)
        throw new AssertionError(s"Expected $ref to use dispatcher [$dispatcher], yet used: [${r.underlying.props.dispatcher}]")
    case _ ⇒
      throw new Exception(s"Unable to determine dispatcher of $ref")
  }
}
