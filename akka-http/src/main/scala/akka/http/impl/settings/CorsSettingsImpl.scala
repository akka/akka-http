/*
 * Copyright (C) 2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.settings

import akka.annotation.InternalApi
import akka.http.impl.util.SettingsCompanionImpl
import akka.http.scaladsl.model.headers.{ HttpOrigin, HttpOriginRange, `Access-Control-Allow-Credentials`, `Access-Control-Allow-Headers`, `Access-Control-Allow-Methods`, `Access-Control-Allow-Origin`, `Access-Control-Expose-Headers`, `Access-Control-Max-Age` }
import akka.http.scaladsl.model.{ HttpHeader, HttpMethod, HttpMethods }
import akka.util.ConstantFun
import com.typesafe.config.Config

import scala.concurrent.duration.{ Duration, FiniteDuration }
import scala.jdk.CollectionConverters._
import scala.jdk.DurationConverters._

/**
 * INTERNAL API
 */
@InternalApi
private[akka] case class CorsSettingsImpl(
  allowGenericHttpRequests: Boolean,
  allowCredentials:         Boolean,
  allowedOrigins:           Set[String],
  allowedHeaders:           Set[String],
  allowedMethods:           Set[HttpMethod],
  exposedHeaders:           Set[String],
  maxAge:                   FiniteDuration
) extends akka.http.scaladsl.settings.CorsSettings {
  import CorsSettingsImpl.allowAnySet

  // internals for the directive impl
  val originsMatches: Seq[HttpOrigin] => Boolean = HttpOriginMatcher(allowedOrigins)
  val headerNameAllowed: String => Boolean =
    if (allowedHeaders == Set("*")) ConstantFun.anyToTrue
    else allowedHeaders.contains

  private def accessControlAllowOrigin(origins: Seq[HttpOrigin]): `Access-Control-Allow-Origin` =
    if (allowedOrigins == allowAnySet && !allowCredentials)
      `Access-Control-Allow-Origin`.*
    else
      `Access-Control-Allow-Origin`.forRange(HttpOriginRange.Default(origins))

  private def accessControlExposeHeaders: Option[`Access-Control-Expose-Headers`] =
    if (exposedHeaders.nonEmpty)
      Some(`Access-Control-Expose-Headers`(exposedHeaders.toArray))
    else
      None

  private def accessControlAllowCredentials: Option[`Access-Control-Allow-Credentials`] =
    if (allowCredentials)
      Some(`Access-Control-Allow-Credentials`(true))
    else
      None

  private def accessControlMaxAge: Option[`Access-Control-Max-Age`] =
    if (maxAge != Duration.Zero) Some(`Access-Control-Max-Age`(maxAge.toSeconds))
    else None

  private def accessControlAllowMethods: `Access-Control-Allow-Methods` =
    `Access-Control-Allow-Methods`(allowedMethods.toArray)

  private def accessControlAllowHeaders(requestHeaders: Seq[String]): Option[`Access-Control-Allow-Headers`] =
    if (allowedHeaders == allowAnySet) {
      if (requestHeaders.nonEmpty) Some(`Access-Control-Allow-Headers`(requestHeaders))
      else None
    } else Some(`Access-Control-Allow-Headers`(requestHeaders))

  // Cache headers that are always included in a preflight response
  private val basePreflightResponseHeaders: List[HttpHeader] =
    List(accessControlAllowMethods) ++ accessControlMaxAge ++ accessControlAllowCredentials

  // Cache headers that are always included in an actual response
  private val baseActualResponseHeaders: List[HttpHeader] =
    accessControlExposeHeaders.toList ++ accessControlAllowCredentials

  def actualResponseHeaders(origins: Seq[HttpOrigin]): List[HttpHeader] =
    accessControlAllowOrigin(origins) :: baseActualResponseHeaders

  def preflightResponseHeaders(origins: Seq[HttpOrigin], requestHeaders: Seq[String]): List[HttpHeader] =
    accessControlAllowHeaders(requestHeaders) match {
      case Some(h) => h :: accessControlAllowOrigin(origins) :: basePreflightResponseHeaders
      case None    => accessControlAllowOrigin(origins) :: basePreflightResponseHeaders
    }
}

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object CorsSettingsImpl extends SettingsCompanionImpl[CorsSettingsImpl]("akka.http.cors") {

  val allowAnySet = Set("*")
  override def fromSubConfig(root: Config, config: Config): CorsSettingsImpl = {
    new CorsSettingsImpl(
      allowGenericHttpRequests = config.getBoolean("allow-generic-http-requests"),
      allowCredentials = config.getBoolean("allow-credentials"),
      allowedOrigins = config.getStringList("allowed-origins").asScala.toSet,
      allowedHeaders = config.getStringList("allowed-headers").asScala.toSet,
      allowedMethods = config.getStringList("allowed-methods").asScala.toSet[String].map(method =>
        HttpMethods.getForKey(method).getOrElse(HttpMethod.custom(method))),
      exposedHeaders = config.getStringList("exposed-headers").asScala.toSet,
      maxAge = config.getDuration("max-age").toScala
    )
  }
}

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object HttpOriginMatcher {
  val matchAny: Seq[HttpOrigin] => Boolean = ConstantFun.anyToTrue

  private def hasWildcard(origin: HttpOrigin): Boolean =
    origin.host.host.isNamedHost && origin.host.host.address.startsWith("*.")

  private def strict(origins: Set[HttpOrigin]): HttpOrigin => Boolean = origins.contains

  private def withWildcards(allowedOrigins: Set[HttpOrigin]): HttpOrigin => Boolean = {
    val matchers = allowedOrigins.map { wildcardOrigin =>
      val suffix = wildcardOrigin.host.host.address.stripPrefix("*")

      (origin: HttpOrigin) =>
        origin.scheme == wildcardOrigin.scheme &&
          origin.host.port == wildcardOrigin.host.port &&
          origin.host.host.address.endsWith(suffix)
    }

    origin => matchers.exists(_.apply(origin))
  }

  def apply(allowedOrigins: Set[String]): Seq[HttpOrigin] => Boolean = {
    if (allowedOrigins == CorsSettingsImpl.allowAnySet) matchAny
    else {
      val httpOrigins = allowedOrigins.map(HttpOrigin.apply)
      val (wildCardAllows, strictAllows) = httpOrigins.partition(hasWildcard)
      val strictMatch = strict(strictAllows)
      val wildCardMatch = withWildcards(wildCardAllows)

      // strict is cheaper so start with those
      val matcher = { (origin: HttpOrigin) => strictMatch(origin) || wildCardMatch(origin) }

      { (origins: Seq[HttpOrigin]) =>
        origins.exists(matcher)
      }
    }
  }
}
