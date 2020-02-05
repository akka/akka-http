/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.server

import org.scalatest.Suite
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

trait GenericRoutingSpec extends Matchers with Directives with ScalatestRouteTest { this: Suite =>
  val Ok = HttpResponse()
  val completeOk = complete(Ok)

  def echoComplete[T]: T => Route = { x => complete(x.toString) }
  def echoComplete2[T, U]: (T, U) => Route = { (x, y) => complete(s"$x $y") }
}

abstract class RoutingSpec extends AnyWordSpec with GenericRoutingSpec
