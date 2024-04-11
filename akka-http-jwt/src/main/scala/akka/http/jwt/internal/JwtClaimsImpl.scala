/*
 * Copyright (C) 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.jwt.internal

import akka.annotation.InternalApi
import akka.http.jwt.scaladsl.server.directives.JwtClaims
import akka.http.jwt.javadsl.server.directives.{ JwtClaims => JavaJwtClaims }
import spray.json.{ JsBoolean, JsNumber, JsObject, JsString }

import java.util.Optional
import scala.compat.java8.OptionConverters.RichOptionForJava8

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

  def rawClaim(name: String): Option[String] = claims.fields.get(name).map(_.toString())

  def toJson: String = claims.toString()
}

/** INTERNAL API */
@InternalApi
private[jwt] object JwtClaimsImpl {
  def toJava(claims: JwtClaims): JavaJwtClaims = new JavaJwtClaims {

    override def hasClaim(name: String): Boolean = claims.hasClaim(name)

    override def getIntClaim(name: String): Optional[Int] = claims.intClaim(name).asJava

    override def getLongClaim(name: String): Optional[Long] = claims.longClaim(name).asJava

    override def getDoubleClaim(name: String): Optional[Double] = claims.doubleClaim(name).asJava

    override def getStringClaim(name: String): Optional[String] = claims.stringClaim(name).asJava

    override def getBooleanClaim(name: String): Optional[Boolean] = claims.booleanClaim(name).asJava

    override def getRawClaim(name: String): Optional[String] = claims.rawClaim(name).asJava

    override def toString: String = claims.toJson
  }
}
