/*
 * Copyright (C) 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.jwt.scaladsl.server.directives

import spray.json.{ JsObject, JsValue }

// JwtClaims provides utilities to easily assert and extract claims from the JWT token
trait JwtClaims {

  def hasClaim(name: String): Boolean

  def intClaim(name: String): Option[Int]

  def longClaim(name: String): Option[Long]

  def doubleClaim(name: String): Option[Double]

  def stringClaim(name: String): Option[String]

  def booleanClaim(name: String): Option[Boolean]

  def rawClaim(name: String): Option[JsValue]

  def stringClaims(name: String): List[String]

}
