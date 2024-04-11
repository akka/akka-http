/*
 * Copyright (C) 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.jwt.internal

import akka.annotation.InternalApi
import akka.http.jwt.scaladsl.server.directives.JwtClaims
import akka.http.jwt.javadsl.server.directives.{ JwtClaims => JavaJwtClaims }
import spray.json.{ JsBoolean, JsNumber, JsObject, JsString }

/**
 * INTERNAL API
 *
 * JwtClaims provides utilities to easily assert and extract claims from the JWT token
 */
@InternalApi
private[jwt] final case class JwtClaimsImpl(claims: JsObject) extends JwtClaims {

  def hasClaim(name: String): Boolean = claims.fields.contains(name)

  def intClaim(name: String): Option[Int] = claims.fields.get(name).collect { case JsNumber(value) => value.toInt }

  def longClaim(name: String): Option[Long] = claims.fields.get(name).collect { case JsNumber(value) => value.toLong }

  def doubleClaim(name: String): Option[Double] = claims.fields.get(name).collect { case JsNumber(value) => value.toDouble }

  def stringClaim(name: String): Option[String] = claims.fields.get(name).collect { case JsString(value) => value }

  def booleanClaim(name: String): Option[Boolean] = claims.fields.get(name).collect { case JsBoolean(value) => value }

  def toJson: String = claims.toString()
}

/** INTERNAL API */
@InternalApi
private[jwt] object JwtClaimsImpl {
  def toJava(claims: JwtClaims): JavaJwtClaims = new JavaJwtClaims {

    override def hasClaim(name: String): Boolean = claims.hasClaim(name)

    override def getIntClaim(name: String): Option[Int] = claims.intClaim(name)

    override def getLongClaim(name: String): Option[Long] = claims.longClaim(name)

    override def getDoubleClaim(name: String): Option[Double] = claims.doubleClaim(name)

    override def getStringClaim(name: String): Option[String] = claims.stringClaim(name)

    override def getBooleanClaim(name: String): Option[Boolean] = claims.booleanClaim(name)
  }
}
