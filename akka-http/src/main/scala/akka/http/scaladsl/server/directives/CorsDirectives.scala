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

/**
 * Directives for CORS, cross origin requests.
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

    def validateOrigins(origins: Seq[HttpOrigin]): List[CorsRejection] =
      if (settingsImpl.originsMatches(origins)) {
        Nil
      } else {
        invalidOriginRejection(origins) :: Nil
      }

    def validateMethod(method: HttpMethod): List[CorsRejection] =
      if (settings.allowedMethods.contains(method)) {
        Nil
      } else {
        invalidMethodRejection(method) :: Nil
      }

    /** Return the list of invalid headers, or `Nil` if they are all valid. */
    def validateHeaders(headers: Seq[String]): List[CorsRejection] = {
      val invalidHeaders = headers.filterNot(settingsImpl.headerNameAllowed)
      if (invalidHeaders.isEmpty) {
        Nil
      } else {
        invalidHeadersRejection(invalidHeaders) :: Nil
      }
    }

    extractRequest.flatMap { request =>
      import request._

      (method, header[Origin].map(_.origins), header[`Access-Control-Request-Method`].map(_.method)) match {
        case (OPTIONS, Some(origins), Some(requestMethod)) if origins.lengthCompare(1) <= 0 =>
          // pre-flight CORS request
          val headers = header[`Access-Control-Request-Headers`].map(_.headers).getOrElse(Seq.empty)

          validateOrigins(origins) ::: validateMethod(requestMethod) ::: validateHeaders(headers) match {
            case Nil    => complete(HttpResponse(StatusCodes.OK, settingsImpl.preflightResponseHeaders(origins, headers)))
            case causes => reject(causes: _*)
          }

        case (_, Some(origins), None) =>
          // actual CORS request
          validateOrigins(origins) match {
            case Nil =>
              mapResponseHeaders { oldHeaders =>
                settingsImpl.actualResponseHeaders(origins) ++ oldHeaders.filterNot(h => CorsDirectives.headersToClean.exists(h.is))
              }
            case causes =>
              reject(causes: _*)
          }

        case _ =>
          if (settings.allowGenericHttpRequests)
            // not a valid CORS request, but allowed
            pass
          else
            // not a valid CORS request, forbidden
            reject(malformedRejection)
      }
    }
  }

  // FIXME should we keep this, fold it into the default rejection handler?
  def corsRejectionHandler: RejectionHandler =
    RejectionHandler
      .newBuilder()
      .handleAll[CorsRejection] { rejections =>
        val causes = rejections.map(_.description).mkString(", ")
        complete((StatusCodes.BadRequest, s"CORS: $causes"))
      }
      .result()

}

object CorsDirectives extends CorsDirectives {
  private val headersToClean: List[String] = List(
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
