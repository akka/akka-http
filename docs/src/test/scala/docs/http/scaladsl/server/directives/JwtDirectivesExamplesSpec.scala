/*
 * Copyright (C) 2009-2025 Lightbend Inc. <https://akka.io>
 */

package docs.http.scaladsl.server.directives

import akka.http.jwt.scaladsl.server.directives.JwtDirectives.jwt
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.server.{ AuthorizationFailedRejection, RoutingSpec }
import docs.CompileOnlySpec

import java.util.Base64

class JwtDirectivesExamplesSpec extends RoutingSpec with CompileOnlySpec {

  override def testConfigSource =
    s"""
       akka.loglevel = DEBUG
       akka.http.jwt {
         dev = off
         secrets: [
           {
             key-id: my-key
             issuer: my-issuer
             algorithm: HS256
             secret: "${Base64.getEncoder.encodeToString("my-secret".getBytes)}"
           }
         ]
       }
      """

  "jwt" in {
    //#jwt
    val route =
      jwt() {
        _.stringClaim("role") match {
          case Some("admin") => complete(s"You're in!")
          case _             => reject(AuthorizationFailedRejection)
        }
      }

    // tests:

    // regular request

    // manually injected valid JWT for test purposes with a claim "role" -> "admin"
    val jwtToken = Authorization(OAuth2BearerToken(
      "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwicm9sZSI6ImFkbWluIn0.6JBvEPNY4KVZpZYfoG6y5UOh3RLUbG-kPyxKHim_La8"))

    Get() ~> addHeader(jwtToken) ~> route ~> check {
      responseAs[String] shouldEqual "You're in!"
    }

    //#jwt
  }
}
