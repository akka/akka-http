/*
 * Copyright (C) 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.jwt.scaladsl.server.directives

import spray.json.JsValue

/**
 * JwtClaims provides a utility to access claims extracted from a JWT token.
 */
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
   * @return an Option containing the integer value of the claim if it exists and is an integer, None otherwise.
   */
  def intClaim(name: String): Option[Int]

  /**
   * Extracts a long claim from the list of claims.
   *
   * @param name the name of the claim.
   * @return an Option containing the long value of the claim if it exists and is a long, None otherwise.
   */
  def longClaim(name: String): Option[Long]

  /**
   * Extracts a double claim from the list of claims.
   *
   * @param name the name of the claim.
   * @return an Option containing the double value of the claim if it exists and is a double, None otherwise.
   */
  def doubleClaim(name: String): Option[Double]

  /**
   * Extracts a string claim from the list of claims.
   *
   * @param name the name of the claim.
   * @return an Option containing the string value of the claim if it exists and is a string, None otherwise.
   */
  def stringClaim(name: String): Option[String]

  /**
   * Extracts a list of string claims from the list of claims.
   *
   * @param name the name of the claim.
   * @return a List containing the string values of the claim if it exists and is a list of strings, empty list otherwise.
   */
  def stringClaims(name: String): List[String]

  /**
   * Extracts a boolean claim from the list of claims.
   *
   * @param name the name of the claim.
   * @return an Option containing the boolean value of the claim if it exists and is a boolean, None otherwise.
   */
  def booleanClaim(name: String): Option[Boolean]

  /**
   * Extracts a raw claim from the list of claims.
   * This can be useful if you need to extract a claim that is not a primitive type but a complex one.
   *
   * @param name the name of the claim.
   * @return an Option containing the raw JsValue of the claim if it exists, None otherwise.
   */
  def rawClaim(name: String): Option[JsValue]

}
