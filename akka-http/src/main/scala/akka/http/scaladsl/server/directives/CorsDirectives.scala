/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.server
package directives

import akka.http.impl.settings.CorsSettingsImpl
import akka.http.scaladsl.model.HttpMethods.OPTIONS
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.model.{ HttpMethod, HttpResponse, StatusCodes }
import akka.http.scaladsl.settings.CorsSettings
import akka.util.OptionVal

/**
 * Directives for CORS, cross origin requests.
 *
 * For an overview on how CORS works, see the MDN web docs page on CORS: https://developer.mozilla.org/en-US/docs/Web/HTTP/CORS
 * CORS is part of the WHATWG Fetch "Living Standard" https://fetch.spec.whatwg.org/#http-cors-protocol
 *
 * @groupname cors CORS directives
 * @groupprio cors 50
 */
trait CorsDirectives {

  import BasicDirectives._
  import CorsDirectives._
  import RouteDirectives._

  /**
   * Wraps its inner route with support for the CORS mechanism, enabling cross origin requests using the default cors
   * configuration from the actor system.
   */
  def cors(): Directive0 = {
    extractActorSystem.flatMap { system =>
      cors(CorsSettings(system))
    }
  }

  /**
   * Wraps its inner route with support for the CORS mechanism, enabling cross origin requests using the given cors
   * settings.
   */
  def cors(settings: CorsSettings): Directive0 = {
    val settingsImpl = settings.asInstanceOf[CorsSettingsImpl]

    def validateOrigins(origins: Seq[HttpOrigin]): OptionVal[CorsRejection] =
      if (settingsImpl.originsMatches(origins)) OptionVal.None
      else OptionVal.Some(invalidOriginRejection(origins))

    def validateMethod(method: HttpMethod): OptionVal[CorsRejection] =
      if (settings.allowedMethods.contains(method)) OptionVal.None
      else OptionVal.Some(invalidMethodRejection(method))

    def validateHeaders(headers: Seq[String]): OptionVal[CorsRejection] = {
      val invalidHeaders = headers.filterNot(settingsImpl.headerNameAllowed)
      if (invalidHeaders.isEmpty) OptionVal.None
      else OptionVal.Some(invalidHeadersRejection(invalidHeaders))
    }

    extractRequest.flatMap { request =>
      val origins = request.header[Origin] match {
        case Some(origin) => OptionVal.Some(origin.origins)
        case None         => OptionVal.None
      }
      val method = request.header[`Access-Control-Request-Method`] match {
        case Some(accessControlMethod) => OptionVal.Some(accessControlMethod.method)
        case None                      => OptionVal.None
      }
      (request.method, origins, method) match {
        case (OPTIONS, OptionVal.Some(origins), OptionVal.Some(requestMethod)) if origins.lengthCompare(1) <= 0 =>
          // pre-flight CORS request
          val headers = request.header[`Access-Control-Request-Headers`] match {
            case Some(header) => header.headers
            case None         => Seq.empty
          }

          val rejections = collectRejections(
            validateOrigins(origins),
            validateMethod(requestMethod),
            validateHeaders(headers))

          if (rejections.isEmpty) {
            complete(HttpResponse(StatusCodes.OK, settingsImpl.preflightResponseHeaders(origins, headers)))
          } else {
            reject(rejections: _*)
          }

        case (_, OptionVal.Some(origins), OptionVal.None) =>
          // actual CORS request
          validateOrigins(origins) match {
            case OptionVal.Some(rejection) =>
              reject(rejection)
            case _ =>
              mapResponseHeaders { oldHeaders =>
                settingsImpl.actualResponseHeaders(origins) ++ oldHeaders.filterNot(h => lcHeaderNamesToClean(h.lowercaseName))
              }
          }

        case _ =>
          // not a valid CORS request, can be allowed through setting
          if (settings.allowGenericHttpRequests) pass
          else reject(malformedRejection)
      }
    }
  }
}

object CorsDirectives extends CorsDirectives {
  private val NoRejections = Array.empty[Rejection]

  // allocation optimized collection of multiple rejections
  private def collectRejections(originsRejection: OptionVal[Rejection], methodRejection: OptionVal[Rejection], headerRejection: OptionVal[Rejection]): Array[Rejection] =
    if (originsRejection.isEmpty && methodRejection.isEmpty && headerRejection.isEmpty) NoRejections
    else {
      def count(opt: OptionVal[_]) = if (opt.isDefined) 1 else 0
      val rejections = Array.ofDim[Rejection](count(originsRejection) + count(methodRejection) + count(headerRejection))
      var idx = 0
      def addIfPresent(opt: OptionVal[Rejection]): Unit =
        if (opt.isDefined) {
          rejections(idx) = opt.get
          idx += 1
        }
      addIfPresent(originsRejection)
      addIfPresent(methodRejection)
      addIfPresent(headerRejection)
      rejections
    }

  private val lcHeaderNamesToClean: Set[String] = Set(
    `Access-Control-Allow-Origin`,
    `Access-Control-Expose-Headers`,
    `Access-Control-Allow-Credentials`,
    `Access-Control-Allow-Methods`,
    `Access-Control-Allow-Headers`,
    `Access-Control-Max-Age`
  ).map(_.lowercaseName)

  private def malformedRejection = CorsRejection("malformed request")

  private def invalidOriginRejection(origins: Seq[HttpOrigin]) = CorsRejection(s"invalid origin '${if (origins.isEmpty) "null" else origins.mkString(" ")}'")

  private def invalidMethodRejection(method: HttpMethod) = CorsRejection(s"invalid method '${method.value}'")

  private def invalidHeadersRejection(headers: Seq[String]) = CorsRejection(s"invalid headers '${headers.mkString(" ")}'")

}
