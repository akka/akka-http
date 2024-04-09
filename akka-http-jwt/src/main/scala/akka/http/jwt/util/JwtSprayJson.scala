/*
 * Copyright 2022 Lightbend Inc.
 */

package akka.http.jwt.util

import pdi.jwt.{ JwtAlgorithm, JwtClaim, JwtHeader, JwtJsonCommon }

import java.time.Clock
import pdi.jwt.exceptions.JwtNonStringException
import spray.json._

/**
 * Implementation of `JwtCore` using `JsObject` from spray-json.
 *
 * This class originally came from jwt-spray-json,
 * but was removed in https://github.com/jwt-scala/jwt-scala/commit/bf1131ce02480103c0b953b97da001105a3ee038
 */
trait JwtSprayJsonParser[H, C] extends JwtJsonCommon[JsObject, H, C] {
  protected def parse(value: String): JsObject = value.parseJson.asJsObject

  protected def stringify(value: JsObject): String = value.compactPrint

  protected def getAlgorithm(header: JsObject): Option[JwtAlgorithm] =
    header.fields.get("alg").flatMap {
      case JsString("none") => None
      case JsString(algo)   => Option(JwtAlgorithm.fromString(algo))
      case JsNull           => None
      case _                => throw new JwtNonStringException("alg")
    }

}

object JwtSprayJson extends JwtSprayJson(Clock.systemUTC) {
  def apply(clock: Clock): JwtSprayJson = new JwtSprayJson(clock)
}

class JwtSprayJson(override val clock: Clock) extends JwtSprayJsonParser[JwtHeader, JwtClaim] {

  import DefaultJsonProtocol._
  override def parseHeader(header: String): JwtHeader = {
    val jsObj = parse(header)
    JwtHeader(
      algorithm = getAlgorithm(jsObj),
      typ = safeGetField[String](jsObj, "typ"),
      contentType = safeGetField[String](jsObj, "cty"),
      keyId = safeGetField[String](jsObj, "kid"))
  }

  override def parseClaim(claim: String): JwtClaim = {
    val jsObj = parse(claim)
    val content = JsObject(jsObj.fields - "iss" - "sub" - "aud" - "exp" - "nbf" - "iat" - "jti")
    JwtClaim(
      content = stringify(content),
      issuer = safeGetField[String](jsObj, "iss"),
      subject = safeGetField[String](jsObj, "sub"),
      audience = safeGetField[Set[String]](jsObj, "aud")
        .orElse(safeGetField[String](jsObj, "aud").map(s => Set(s))),
      expiration = safeGetField[Long](jsObj, "exp"),
      notBefore = safeGetField[Long](jsObj, "nbf"),
      issuedAt = safeGetField[Long](jsObj, "iat"),
      jwtId = safeGetField[String](jsObj, "jti"))
  }

  private[this] def safeRead[A: JsonReader](js: JsValue) =
    safeReader[A].read(js).fold(_ => None, a => Option(a))

  private[this] def safeGetField[A: JsonReader](js: JsObject, name: String) =
    js.fields.get(name).flatMap(safeRead[A])
}
