/*
 * Copyright (C) 2019-2025 Lightbend Inc. <https://akka.io>
 */

package akka.http.scaladsl.server
package directives

import akka.http.scaladsl.model.FormData
import akka.http.scaladsl.server.RoutingSpec

class CompatFormFieldSpec extends RoutingSpec {
  def test(): Unit =
    if (!scala.util.Properties.versionNumberString.startsWith("2.13")) {
      "for one parameter" in {
        val req = Post("/", FormData("num" -> "12"))
        req ~> CompatFormField.oneParameter(echoComplete) ~> check {
          responseAs[String] shouldEqual "12"
        }
        req ~> CompatFormField.oneParameterRoute ~> check {
          responseAs[String] shouldEqual "12"
        }
      }
    } else
      "ignore incompatiblity in 2.13" in succeed

  "FormFieldDirectives" should {
    "be compatible" should {
      test()
    }
  }
}
