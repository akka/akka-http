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
abstract class CorsSettings private[akka] () { self: CorsSettingsImpl =>
  import akka.http.impl.util.JavaMapping.Implicits._

  /**
   * Allow generic requests (that are outside the scope of the specification) to pass through the directive.
   * When false strict CORS filtering is applied and any invalid request will be rejected
   */
  def allowGenericHttpRequests: Boolean

  /**
   * Indicates whether the resource supports user credentials. If `true`, the header `Access-Control-Allow-Credentials`
   * is set in the response, indicating that the actual request can include user credentials. Examples of user
   * credentials are: cookies, HTTP authentication or client-side certificates.
   *
   * @see [[https://www.w3.org/TR/cors/#access-control-allow-credentials-response-header Access-Control-Allow-Credentials]]
   */
  def allowCredentials: Boolean

  /**
   * List of origins that the CORS filter must allow. Can also be set to `*` to allow access to the resource from any
   * origin. Controls the content of the `Access-Control-Allow-Origin` response header: if parameter is `*` and
   * credentials are not allowed, a `*` is set in `Access-Control-Allow-Origin`. Otherwise, the origins given in the
   * `Origin` request header are echoed.
   *
   * Hostname starting with `*.` will match any sub-domain. The scheme and the port are always strictly matched.
   *
   * The actual or preflight request is rejected if any of the origins from the request is not allowed.
   *
   * @see [[https://www.w3.org/TR/cors/#access-control-allow-origin-response-header Access-Control-Allow-Origin]]
   */
  def getAllowedOrigins: JSet[String] = self.allowedOrigins.asJava

  /**
   * List of request headers that can be used when making an actual request. Controls the content of the
   * `Access-Control-Allow-Headers` header in a preflight response: if parameter is `*`, the headers from
   * `Access-Control-Request-Headers` are echoed. Otherwise the parameter list is returned as part of the header.
   *
   * @see [[https://www.w3.org/TR/cors/#access-control-allow-headers-response-header Access-Control-Allow-Headers]]
   */
  def getAllowedHeaders: JSet[String] = self.allowedHeaders.asJava

  /**
   * List of methods that can be used when making an actual request. The list is returned as part of the
   * `Access-Control-Allow-Methods` preflight response header.
   *
   * The preflight request will be rejected if the `Access-Control-Request-Method` header's method is not part of the
   * list.
   *
   * Default: `Seq(GET, POST, HEAD, OPTIONS)`
   *
   * @see
   *   [[https://www.w3.org/TR/cors/#access-control-allow-methods-response-header Access-Control-Allow-Methods]]
   */
  def getAllowedMethods: JSet[HttpMethod] = self.allowedMethods.map(_.asJava).asJava

  /**
   * List of headers (other than simple response headers) that browsers are allowed to access. If not empty, this list
   * is returned as part of the `Access-Control-Expose-Headers` header in the actual response.
   *
   * @see
   *   [[https://www.w3.org/TR/cors/#simple-response-header Simple response headers]]
   * @see
   *   [[https://www.w3.org/TR/cors/#access-control-expose-headers-response-header Access-Control-Expose-Headers]]
   */
  def getExposedHeaders: JSet[String] = self.exposedHeaders.asJava

  /**
   * When set, the time the browser is allowed to cache the results of a preflight request. This value is
   * returned as part of the `Access-Control-Max-Age` preflight response header. If `Duration.ZERO`, the header is not added to
   * the preflight response.
   *
   * @see
   *   [[https://www.w3.org/TR/cors/#access-control-max-age-response-header Access-Control-Max-Age]]
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
