/*
 * Copyright (C) 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.jwt.javadsl.server.directives

abstract class JwtClaims {

  def hasClaim(name: String): Boolean

  def getIntClaim(name: String): Option[Int]

  def getLongClaim(name: String): Option[Long]

  def getDoubleClaim(name: String): Option[Double]

  def getStringClaim(name: String): Option[String]

  def getBooleanClaim(name: String): Option[Boolean]

}
