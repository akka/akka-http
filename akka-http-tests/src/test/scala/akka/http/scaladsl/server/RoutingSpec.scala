/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.server

import akka.http.impl.util.WithLogCapturing
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.testkit.TestKitBase
import org.scalatest.Suite
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

trait GenericRoutingSpec extends Matchers with Directives with ScalatestRouteTest { this: Suite =>
  val Ok = HttpResponse()
  val completeOk = complete(Ok)

  def echoComplete[T]: T => Route = { x => complete(x.toString) }
  def echoComplete2[T, U]: (T, U) => Route = { (x, y) => complete(s"$x $y") }
}

// FIXME: currently cannot use `AkkaSpec` or `AkkaSpecWithMaterializer`, see https://github.com/akka/akka-http/issues/3313
abstract class RoutingSpec extends AnyWordSpec with GenericRoutingSpec with WithLogCapturing with TestKitBase with ScalaFutures {
  override def testConfigSource: String =
    """
       akka.loglevel = DEBUG
       akka.loggers = ["akka.http.impl.util.SilenceAllTestEventListener"]
    """

  implicit val patience: PatienceConfig = PatienceConfig(testKitSettings.DefaultTimeout.duration)
}
