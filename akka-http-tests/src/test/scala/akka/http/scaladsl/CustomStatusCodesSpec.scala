/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl

import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives
import akka.http.scaladsl.settings.ClientConnectionSettings
import akka.http.scaladsl.settings.ConnectionPoolSettings
import akka.stream.ActorMaterializer
import akka.testkit.{ AkkaSpec, SocketUtil }
import org.scalatest.concurrent.ScalaFutures

class CustomStatusCodesSpec extends AkkaSpec with ScalaFutures
  with Directives with RequestBuilding {

  implicit val mat = ActorMaterializer()

  "Http" should {
    "allow registering custom status code" in {
      import system.dispatcher
      val (host, port) = SocketUtil.temporaryServerHostnameAndPort()

      //#application-custom
      // similarily in Java: `akka.http.javadsl.settings.[...]`
      import akka.http.scaladsl.settings.{ ParserSettings, ServerSettings }

      // define custom status code:
      val LeetCode = StatusCodes.custom(777, "LeetCode", "Some reason", isSuccess = true, allowsEntity = false)

      // add custom method to parser settings:
      val parserSettings = ParserSettings(system).withCustomStatusCodes(LeetCode)
      val serverSettings = ServerSettings(system).withParserSettings(parserSettings)

      val clientConSettings = ClientConnectionSettings(system).withParserSettings(parserSettings)
      val clientSettings = ConnectionPoolSettings(system).withConnectionSettings(clientConSettings)

      val routes =
        complete(HttpResponse(status = LeetCode))

      // use serverSettings in server:
      val binding = Http().bindAndHandle(routes, host, port, settings = serverSettings)

      // use clientSettings in client:
      val request = HttpRequest(uri = s"http://$host:$port/")
      val response = Http().singleRequest(request, settings = clientSettings)

      // futureValue is a ScalaTest helper:
      response.futureValue.status should ===(LeetCode)
      //#application-custom
      binding.foreach(_.unbind())
    }
  }

}

