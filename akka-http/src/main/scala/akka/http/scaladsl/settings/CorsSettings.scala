package akka.http.scaladsl.settings

import akka.actor.ClassicActorSystemProvider
import akka.annotation.{ ApiMayChange, DoNotInherit }
import akka.http.impl.settings.CorsSettingsImpl
import akka.http.scaladsl.model.HttpMethod
import com.typesafe.config.Config

import scala.concurrent.duration.FiniteDuration

/**
 * Settings for the CORS support
 *
 * Not for user extension
 */
@ApiMayChange @DoNotInherit
trait CorsSettings extends akka.http.javadsl.settings.CorsSettings { self: CorsSettingsImpl =>
  /**
   * Allow generic requests (that are outside the scope of the specification) to pass through the directive.
   * When false strict CORS filtering is applied and any invalid request will be rejected
   */
  override def allowGenericHttpRequests: Boolean

  /**
   * Indicates whether the resource supports user credentials. If `true`, the header `Access-Control-Allow-Credentials`
   * is set in the response, indicating that the actual request can include user credentials. Examples of user
   * credentials are: cookies, HTTP authentication or client-side certificates.
   *
   * @see [[https://www.w3.org/TR/cors/#access-control-allow-credentials-response-header Access-Control-Allow-Credentials]]
   */
  override def allowCredentials: Boolean

  /**
   * List of origins that the CORS filter must allow.
   *
   * Can also be set to a single `*` to allow access to the resource from any
   * origin. Controls the content of the `Access-Control-Allow-Origin` response header: if parameter is `*` and
   * credentials are not allowed, a `*` is set in `Access-Control-Allow-Origin`. Otherwise, the origins given in the
   * `Origin` request header are echoed.
   *
   * Hostname starting with `*.` will match any sub-domain. The scheme and the port are always strictly matched.
   *
   * The actual or preflight request is rejected if any of the origins from the request is not allowed.
   */
  override def allowedOrigins: Set[String]

  /**
   * Set of request headers that are allowed when making an actual request. Controls the content of the
   * `Access-Control-Allow-Headers` header in a preflight response. If parameter is a single `*`, the headers from
   * `Access-Control-Request-Headers` are echoed. Otherwise the parameter list is returned as part of the header.
   */
  override def allowedHeaders: Set[String]

  /**
   * List of methods allowed when making an actual request. The listed headers are returned as part of the
   * `Access-Control-Allow-Methods` preflight response header.
   *
   * The preflight request will be rejected if the `Access-Control-Request-Method` header's method is not part of the
   * list.
   */
  override def allowedMethods: Set[HttpMethod]

  /**
   * Set of headers (other than simple response headers) that browsers are allowed to access. If not empty, the listed
   * headers are returned as part of the `Access-Control-Expose-Headers` header in responses.
   */
  override def exposedHeaders: Set[String]

  /**
   * The time the browser is allowed to cache the results of a preflight request. This value is
   * returned as part of the `Access-Control-Max-Age` preflight response header. If `scala.concurrent.duration.Duration.Zero`,
   * the header is not added to the preflight response.
   */
  override def maxAge: FiniteDuration

  def withMaxAge(maxAge: FiniteDuration): CorsSettings =
    self.copy(maxAge = maxAge)

  override def withAllowAnyOrigin(): CorsSettings =
    self.copy(allowedOrigins = Set("*"))

  def withAllowedOrigins(origins: Set[String]): CorsSettings =
    self.copy(allowedOrigins = origins)

  override def withAllowAnyHeader(): CorsSettings =
    self.copy(allowedHeaders = Set("*"))
  def withAllowedHeaders(headerNames: Set[String]): CorsSettings =
    self.copy(allowedHeaders = headerNames)
  def withAllowedMethods(methods: Set[HttpMethod]): CorsSettings =
    self.copy(allowedMethods = methods)

  def withExposedHeaders(headerNames: Set[String]): CorsSettings =
    self.copy(exposedHeaders = headerNames)

  override def withAllowGenericHttpRequests(allow: Boolean): CorsSettings =
    self.copy(allowGenericHttpRequests = allow)

  override def withAllowCredentials(allow: Boolean): CorsSettings =
    self.copy(allowCredentials = allow)

}

object CorsSettings {
  def apply(system: ClassicActorSystemProvider): CorsSettings =
    CorsSettingsImpl(system.classicSystem)
  def apply(config: Config): CorsSettings =
    CorsSettingsImpl(config)
}
