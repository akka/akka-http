/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.ws

import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.BeforeAndAfterAll
import akka.actor.ActorSystem
import akka.http.impl.util.WithLogCapturing
import akka.stream.ActorMaterializer
import akka.testkit.EventFilter
import akka.testkit.TestKit
import org.scalatest.TestSuite

trait WithMaterializerSpec extends BeforeAndAfterAll with WithLogCapturing { _: TestSuite =>
  lazy val testConf: Config = ConfigFactory.parseString("""
  akka.loggers = ["akka.http.impl.util.SilenceAllTestEventListener"]
  akka.loglevel = debug
  """)
  implicit lazy val system = ActorSystem(getClass.getSimpleName, testConf)

  implicit lazy val materializer = ActorMaterializer()
  override def afterAll() =
    // don't log anything during shutdown
    EventFilter.custom { case x => true }.intercept {
      // shutdown materializer first, otherwise it will only be shutdown during
      // main system guardian being shutdown which will be after the logging has
      // reverted to stdout logging that cannot be intercepted
      materializer.shutdown()
      // materializer shutdown is async but cannot be watched
      Thread.sleep(10)

      TestKit.shutdownActorSystem(system)
    }
}
