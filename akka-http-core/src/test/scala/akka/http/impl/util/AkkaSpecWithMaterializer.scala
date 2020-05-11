/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.util

import akka.stream.{ ActorMaterializer, SystemMaterializer }
import akka.testkit.AkkaSpec
import akka.testkit.EventFilter

abstract class AkkaSpecWithMaterializer(s: String)
  extends AkkaSpec(s +
    """
       akka.loglevel = DEBUG
       akka.loggers = ["akka.http.impl.util.SilenceAllTestEventListener"]""") with WithLogCapturing {

  def this() = this("")

  implicit val materializer = SystemMaterializer(system).materializer

  override protected def beforeTermination(): Unit =
    // don't log anything during shutdown, especially not AbruptTerminationExceptions
    EventFilter.custom { case x => true }.intercept {
      // shutdown materializer first, otherwise it will only be shutdown during
      // main system guardian being shutdown which will be after the logging has
      // reverted to stdout logging that cannot be intercepted
      materializer.asInstanceOf[ActorMaterializer].shutdown()
      // materializer shutdown is async but cannot be watched
      Thread.sleep(10)
    }
}
