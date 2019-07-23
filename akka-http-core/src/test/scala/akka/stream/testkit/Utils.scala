/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.testkit

import akka.actor.{ ActorRef, ActorRefWithCell }
import akka.stream.Materializer
import com.typesafe.config.ConfigFactory

import scala.util.control.NoStackTrace

object Utils {

  /** Sets the default-mailbox to the usual [[akka.dispatch.UnboundedMailbox]] instead of [[StreamTestDefaultMailbox]]. */
  val UnboundedMailboxConfig = ConfigFactory.parseString("""akka.actor.default-mailbox.mailbox-type = "akka.dispatch.UnboundedMailbox"""")

  case class TE(message: String) extends RuntimeException(message) with NoStackTrace

  def assertAllStagesStopped[T](block: => T)(implicit materializer: Materializer): T =
    scaladsl.StreamTestKit.assertAllStagesStopped(block)

  def assertDispatcher(ref: ActorRef, dispatcher: String): Unit = ref match {
    case r: ActorRefWithCell =>
      if (r.underlying.props.dispatcher != dispatcher)
        throw new AssertionError(s"Expected $ref to use dispatcher [$dispatcher], yet used: [${r.underlying.props.dispatcher}]")
    case _ =>
      throw new Exception(s"Unable to determine dispatcher of $ref")
  }
}
