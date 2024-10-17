/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.testkit

import scala.concurrent.duration._
import akka.actor.ActorSystem
import akka.http.impl.util.enhanceConfig

case class RouteTestTimeout(duration: FiniteDuration)

object RouteTestTimeout {

  implicit def default(implicit system: ActorSystem): RouteTestTimeout = {
    val routesTimeout = system.settings.config.getFiniteDuration("akka.http.testkit.routes.timeout")
    RouteTestTimeout(routesTimeout)
  }
}
