/*
 * Copyright (C) 2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.settings

import akka.annotation.DoNotInherit
import akka.http.impl.settings.CorsSettingsImpl
import akka.http.javadsl.model.HttpMethod

import java.time.Duration
import java.util.{ Set => JSet }
import scala.jdk.CollectionConverters.{ SetHasAsJava, SetHasAsScala }
import scala.jdk.DurationConverters.ScalaDurationOps

/**
 * Settings for the CORS support
 *
 * Not for user extension
 */
@DoNotInherit
abstract class CorsSettings private[akka] { self: CorsSettingsImpl =>
  import akka.http.impl.util.JavaMapping.Implicits._

  /**
   * Allow generic requests, that are outside the scope of the specification, for example lacking an `Origin` header
   * to pass through the directive.
   *
   * When false strict CORS filtering is applied and any invalid request will be rejected.
   */
  def allowGenericHttpRequests: Boolean

  /**
   * If enabled, the header `Access-Control-Allow-Credentials`
   * is included in the response, indicating that the actual request can include user credentials. Examples of user
   * credentials are: cookies, HTTP authentication or client-side certificates.
   */
  def allowCredentials: Boolean

  /**
   * List of origins that the CORS filter must allow.
   *
   * Can also be set to a single `*` to allow access to the resource from any origin.
   *
   * Controls the content of the `Access-Control-Allow-Origin` response header: if parameter is `*` and
   * credentials are not allowed, a `*` is returned in `Access-Control-Allow-Origin`. Otherwise, the origins given in the
   * `Origin` request header are echoed.
   *
   * Hostname starting with `*.` will match any sub-domain. The scheme and the port are always strictly matched.
   *
   * The actual or preflight request is rejected if any of the origins from the request is not allowed.
   */
  def getAllowedOrigins: JSet[String] = self.allowedOrigins.asJava

  /**
   * Set of request headers that are allowed when making an actual request.
   *
   * Controls the content of the `Access-Control-Allow-Headers` header in a preflight response: If set to a single `*`,
   * the headers from `Access-Control-Request-Headers` are echoed. Otherwise specified list of header names is returned
   * as part of the header.
   */
  def getAllowedHeaders: JSet[String] = self.allowedHeaders.asJava

  /**
   * List of methods allowed when making an actual request. The listed headers are returned as part of the
   * `Access-Control-Allow-Methods` preflight response header.
   *
   * The preflight request will be rejected if the `Access-Control-Request-Method` header's method is not part of the
   * list.
   */
  def getAllowedMethods: JSet[HttpMethod] = self.allowedMethods.map(_.asJava).asJava

  /**
   * Set of headers (other than simple response headers) that browsers are allowed to access. If not empty, the listed
   * headers are returned as part of the `Access-Control-Expose-Headers` header in responses.
   */
  def getExposedHeaders: JSet[String] = self.exposedHeaders.asJava

  /**
   * The time the browser is allowed to cache the results of a preflight request. This value is
   * returned as part of the `Access-Control-Max-Age` preflight response header. If `java.time.Duration.ZERO`,
   * the header is not added to the preflight response.
   */
  def getMaxAge: Duration = self.maxAge.toJava

  def withAllowAnyHeader(): CorsSettings =
    self.copy(allowedHeaders = Set("*"))

  def withAllowedHeaders(headerNames: JSet[String]): CorsSettings =
    self.copy(allowedHeaders = headerNames.asScala.toSet)

  def withAllowAnyOrigin(): CorsSettings =
    self.copy(allowedOrigins = Set("*"))

  def withAllowedOrigins(origins: JSet[String]): CorsSettings =
    self.copy(allowedOrigins = origins.asScala.toSet)

  def withAllowedMethods(methods: JSet[HttpMethod]): CorsSettings =
    self.copy(allowedMethods = methods.asScala.toSet[HttpMethod].map(_.asScala))

  def withExposedHeaders(headerNames: JSet[String]): CorsSettings =
    self.copy(exposedHeaders = headerNames.asScala.toSet)

  def withAllowGenericHttpRequests(allow: Boolean): CorsSettings =
    self.copy(allowGenericHttpRequests = allow)

  def withAllowCredentials(allow: Boolean): CorsSettings =
    self.copy(allowCredentials = allow)
}
