/*
 * Copyright (C) 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.jwt.javadsl.server.directives

import java.util.Optional

/**
 * JwtClaims provides a utility to access claims extracted from a JWT token.
 * Not for user extension
 */
@DoNotInherit
trait JwtClaims {

  /**
   * Checks if a claim with the given name exists in the list of claims.
   *
   * @param name the name of the claim.
   * @return true if the claim exists, false otherwise.
   */
  def hasClaim(name: String): Boolean

  /**
   * Extracts an integer claim from the list of claims.
   *
   * @param name the name of the claim.
   * @return an Optional containing the integer value of the claim if it exists and is an integer, Optional.empty otherwise.
   */
  def getIntClaim(name: String): Optional[Int]

  /**
   * Extracts a long claim from the list of claims.
   *
   * @param name the name of the claim.
   * @return an Optional containing the long value of the claim if it exists and is a long, Optional.empty otherwise.
   */
  def getLongClaim(name: String): Optional[Long]

  /**
   * Extracts a double claim from the list of claims.
   *
   * @param name the name of the claim.
   * @return an Optional containing the double value of the claim if it exists and is a double, Optional.empty otherwise.
   */
  def getDoubleClaim(name: String): Optional[Double]

  /**
   * Extracts a string claim from the list of claims.
   *
   * @param name the name of the claim.
   * @return an Optional containing the string value of the claim if it exists and is a string, Optional.empty otherwise.
   */
  def getStringClaim(name: String): Optional[String]

  /**
   * Extracts a list of string claims from the list of claims.
   *
   * @param name the name of the claim.
   * @return a List containing the string values of the claim if it exists and is a list of strings, empty list otherwise.
   */
  def getStringClaims(name: String): java.util.List[String]

  /**
   * Extracts a boolean claim from the list of claims.
   *
   * @param name the name of the claim.
   * @return an Optional containing the boolean value of the claim if it exists and is a boolean, Optional.empty otherwise.
   */
  def getBooleanClaim(name: String): Optional[Boolean]

  /**
   * Extracts a raw claim from the list of claims.
   * This can be useful if you need to extract a claim that is not a primitive type but a complex one.
   *
   * @param name the name of the claim.
   * @return an Optional containing the raw JSON String value of the claim if it exists, Optional.empty otherwise.
   */
  def getRawClaim(name: String): Optional[String]
}
