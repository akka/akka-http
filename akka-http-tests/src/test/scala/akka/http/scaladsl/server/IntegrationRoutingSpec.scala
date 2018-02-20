/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.server

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse }
import akka.stream.ActorMaterializer
import akka.testkit.{ AkkaSpec, SocketUtil, TestKit }
import org.scalatest.concurrent.{ IntegrationPatience, ScalaFutures }
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpecLike }

/** not (yet?) ready for public consuption */
private[akka] trait IntegrationRoutingSpec extends WordSpecLike with Matchers with BeforeAndAfterAll
  with Directives with RequestBuilding
  with ScalaFutures with IntegrationPatience {
  import IntegrationRoutingSpec._

  implicit val system = ActorSystem(AkkaSpec.getCallerName(getClass))
  implicit val mat = ActorMaterializer()
  import system.dispatcher

  override protected def afterAll() = TestKit.shutdownActorSystem(system)

  implicit class DSL(request: HttpRequest) {
    def ~!>(route: Route) = new Prepped(request, route)
  }

  implicit class Checking(p: Prepped) {
    def ~!>(checking: HttpResponse ⇒ Unit) = {
      val (host, port) = SocketUtil.temporaryServerHostnameAndPort()
      val binding = Http().bindAndHandle(p.route, host, port)

      try {
        val targetUri = p.request.uri.withHost(host).withPort(port).withScheme("http")
        val response = Http().singleRequest(p.request.withUri(targetUri)).futureValue
        checking(response)
      } finally binding.flatMap(_.unbind()).futureValue
    }
  }
}

object IntegrationRoutingSpec {
  final case class Prepped(request: HttpRequest, route: Route)
}
