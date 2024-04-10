package akka.http.jwt.scaladsl.server.directives

import spray.json.{JsBoolean, JsNumber, JsObject, JsString}

// JwtClaims provides utilities to easily assert and extract claims from the JWT token
class JwtClaims(claims: JsObject) {

  def hasClaim(name: String): Boolean = claims.fields.contains(name)

  def intClaim(name: String): Option[Int] = claims.fields.get(name).collect { case JsNumber(value) => value.toInt }

  def longClaim(name: String): Option[Long] = claims.fields.get(name).collect { case JsNumber(value) => value.toLong }

  def doubleClaim(name: String): Option[Double] = claims.fields.get(name).collect { case JsNumber(value) => value.toDouble }

  def stringClaim(name: String): Option[String] = claims.fields.get(name).collect { case JsString(value) => value }

  def booleanClaim(name: String): Option[Boolean] = claims.fields.get(name).collect { case JsBoolean(value) => value }

  def toJson: String = claims.toString()
}