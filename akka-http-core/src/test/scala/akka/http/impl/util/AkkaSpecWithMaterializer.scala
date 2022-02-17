/*
 * Copyright (C) 2019-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.util

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.stream.{ ActorMaterializer, Materializer, SystemMaterializer }
import akka.testkit.AkkaSpec
import akka.testkit.EventFilter
import com.typesafe.config.ConfigFactory

abstract class AkkaSpecWithMaterializer(configOverrides: String)
  extends AkkaSpec(
    ActorSystem(
      AkkaSpecWithMaterializer.callerName(),
      ConfigFactory.load(ConfigFactory.parseString(
        configOverrides +
          """
            akka.loglevel = DEBUG
            akka.loggers = ["akka.http.impl.util.SilenceAllTestEventListener"]
          """).withFallback(AkkaSpec.testConf))
    )) with WithLogCapturing {

  def this() = this("")

  implicit val materializer: Materializer = SystemMaterializer(system).materializer

  override protected def beforeTermination(): Unit =
    // don't log anything during shutdown, especially not AbruptTerminationExceptions
    EventFilter.custom { case x => true }.intercept {
      // shutdown materializer first, otherwise it will only be shutdown during
      // main system guardian being shutdown which will be after the logging has
      // reverted to stdout logging that cannot be intercepted
      materializer.asInstanceOf[ActorMaterializer].shutdown()
      Http().shutdownAllConnectionPools()
      // materializer shutdown is async but cannot be watched
      Thread.sleep(10)
    }
}
object AkkaSpecWithMaterializer {
  // adapted version of AkkaSpec.getCallerName that also works for `AkkaSpecWithMaterializer`
  def callerName(): String =
    (Thread.currentThread.getStackTrace map (_.getClassName) drop 1)
      .dropWhile(_ matches "(java.lang.Thread|.*AkkaSpecWithMaterializer.?$|.*StreamSpec.?$)")
      .head.replaceFirst(""".*\.""", "").replaceAll("[^a-zA-Z_0-9]", "_")
}
