/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.scaladsl.server.directives

import akka.http.jwt.scaladsl.server.directives.JwtDirectives.jwt
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.server.{AuthorizationFailedRejection, RoutingSpec}
import docs.CompileOnlySpec

class JwtDirectivesExamplesSpec extends RoutingSpec with CompileOnlySpec {
  "jwt" in {
    //#jwt
    val route =
      jwt() { _.stringClaim("role") match {
          case Some("admin") => complete(s"You're in!")
          case None          => reject(AuthorizationFailedRejection)
        }
      }

    // tests:

    // regular request

    // manually injected valid JWT for test purposes with a claim "role" -> "admin"
    val jwtToken = Authorization(OAuth2BearerToken(
      "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIn0.Gfx6VO9tcxwk6xqx9yYzSfebfeakZp5JYIgP_edcw_A"))

    Get() ~> addHeader(jwtToken) ~> route ~> check {
      responseAs[String] shouldEqual "You're in!"
    }

    //#jwt
  }
}
