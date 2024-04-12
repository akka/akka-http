/*
 * Copyright (C) 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.jwt.javadsl.server.directives

import java.util.Optional

trait JwtClaims {

  def hasClaim(name: String): Boolean

  def getIntClaim(name: String): Optional[Int]

  def getLongClaim(name: String): Optional[Long]

  def getDoubleClaim(name: String): Optional[Double]

  def getStringClaim(name: String): Optional[String]

  def getStringClaims(name: String): java.util.List[String]

  def getBooleanClaim(name: String): Optional[Boolean]

  def getRawClaim(name: String): Optional[String]
}
