/*
 * Copyright (C) 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.jwt.internal

import akka.annotation.InternalApi
import akka.http.jwt.scaladsl.server.directives.JwtClaims
import akka.http.jwt.javadsl.server.directives.{ JwtClaims => JavaJwtClaims }

import spray.json.{ JsArray, JsBoolean, JsNumber, JsObject, JsString, JsValue }

import java.util.Optional

import scala.jdk.CollectionConverters.SeqHasAsJava
import scala.jdk.OptionConverters._

/**
 * INTERNAL API
 *
 * JwtClaims provides utilities to easily assert and extract claims from the JWT token
 */
@InternalApi
private[jwt] final case class JwtClaimsImpl(claims: JsObject) extends JwtClaims with JavaJwtClaims {

  override def hasClaim(name: String): Boolean = claims.fields.contains(name)

  override def intClaim(name: String): Option[Int] = claims.fields.get(name).collect { case JsNumber(value) => value.toInt }

  override def longClaim(name: String): Option[Long] = claims.fields.get(name).collect { case JsNumber(value) => value.toLong }

  override def doubleClaim(name: String): Option[Double] = claims.fields.get(name).collect { case JsNumber(value) => value.toDouble }

  override def stringClaim(name: String): Option[String] = claims.fields.get(name).collect { case JsString(value) => value }

  override def stringClaims(name: String): List[String] = claims.fields.get(name)
    .collect {
      case JsArray(elems) =>
        elems.collect { case JsString(value) => value }
    }
    .map(_.toList)
    .getOrElse(List.empty[String])

  override def booleanClaim(name: String): Option[Boolean] = claims.fields.get(name).collect { case JsBoolean(value) => value }

  override def rawClaim(name: String): Option[JsValue] = claims.fields.get(name)

  // JAVA API
  override def getIntClaim(name: String): Optional[Int] = intClaim(name).toJava

  override def getLongClaim(name: String): Optional[Long] = longClaim(name).toJava

  override def getDoubleClaim(name: String): Optional[Double] = doubleClaim(name).toJava

  override def getStringClaim(name: String): Optional[String] = stringClaim(name).toJava

  override def getStringClaims(name: String): java.util.List[String] = stringClaims(name).asJava

  override def getBooleanClaim(name: String): Optional[Boolean] = booleanClaim(name).toJava

  override def getRawClaim(name: String): Optional[String] = rawClaim(name).map(_.toString).toJava
}
