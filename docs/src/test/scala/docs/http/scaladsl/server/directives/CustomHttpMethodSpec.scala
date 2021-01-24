/*
 * Copyright (C) 2009-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.scaladsl.server.directives

import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpProtocols._
import akka.http.scaladsl.model.RequestEntityAcceptance.Expected
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives
import akka.testkit.{ AkkaSpec, SocketUtil }
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.duration._

class CustomHttpMethodSpec extends AkkaSpec with ScalaFutures
  with Directives {

  "Http" should {
    "allow registering custom method" in {
      import system.dispatcher
      val host = "localhost"
      var port = 0
      //#application-custom
      import akka.http.scaladsl.settings.{ ParserSettings, ServerSettings }

      // define custom method type:
      val BOLT = HttpMethod.custom("BOLT", safe = false,
        idempotent = true, requestEntityAcceptance = Expected)

      // add custom method to parser settings:
      val parserSettings = ParserSettings.forServer(system).withCustomMethods(BOLT)
      val serverSettings = ServerSettings(system).withParserSettings(parserSettings)

      val routes = extractMethod { method =>
        complete(s"This is a ${method.name} method request.")
      }
      val binding = Http().newServerAt(host, port).withSettings(serverSettings).bind(routes)

      //#application-custom
      // Make sure we're bound
      port = binding.futureValue.localAddress.getPort
      //#application-custom
      val request = HttpRequest(BOLT, s"http://$host:$port/", protocol = `HTTP/1.1`)
      //#application-custom

      // Check response
      val response = Http().singleRequest(request).futureValue
      response.status should ===(StatusCodes.OK)

      val responseBody = response.entity.toStrict(1.second).futureValue.data.utf8String
      responseBody should ===("This is a BOLT method request.")
    }
  }
}

