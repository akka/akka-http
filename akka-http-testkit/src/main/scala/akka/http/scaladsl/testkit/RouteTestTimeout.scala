/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.testkit

import scala.concurrent.duration._
import akka.actor.ActorSystem
import akka.testkit._

case class RouteTestTimeout(duration: FiniteDuration)

object RouteTestTimeout {
  implicit def default(implicit system: ActorSystem): RouteTestTimeout = RouteTestTimeout(1.second.dilated)
}
