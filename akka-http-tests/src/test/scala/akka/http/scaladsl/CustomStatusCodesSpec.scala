/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl

import akka.http.impl.util.AkkaSpecWithMaterializer
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives
import akka.http.scaladsl.settings.{ ClientConnectionSettings, ConnectionPoolSettings }
import org.scalatest.concurrent.ScalaFutures

class CustomStatusCodesSpec extends AkkaSpecWithMaterializer with ScalaFutures
  with Directives with RequestBuilding {

  "Http" should {
    "allow registering custom status code" in {
      //#application-custom
      // similarly in Java: `akka.http.javadsl.settings.[...]`
      import akka.http.scaladsl.settings.{ ParserSettings, ServerSettings }

      // define custom status code:
      val LeetCode = StatusCodes.custom(777, "LeetCode", "Some reason", isSuccess = true, allowsEntity = false)

      // add custom method to parser settings:
      val parserSettings = ParserSettings.forServer(system).withCustomStatusCodes(LeetCode)
      val serverSettings = ServerSettings(system).withParserSettings(parserSettings)

      val clientConSettings = ClientConnectionSettings(system).withParserSettings(parserSettings)
      val clientSettings = ConnectionPoolSettings(system).withConnectionSettings(clientConSettings)

      val routes =
        complete(HttpResponse(status = LeetCode))

      // use serverSettings in server:
      val binding = Http().newServerAt("127.0.0.1", 0).withSettings(serverSettings).bind(routes).futureValue

      // use clientSettings in client:
      val request = HttpRequest(uri = s"http://127.0.0.1:${binding.localAddress.getPort}/")
      val response = Http().singleRequest(request, settings = clientSettings)

      // futureValue is a ScalaTest helper:
      response.futureValue.status should ===(LeetCode)
      //#application-custom
      binding.unbind()
    }
  }

}

