/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.model.http2

import akka.http.impl.engine.ws.InternalCustomHeader
import akka.http.scaladsl.model.HttpRequest

final case class Http2PushRequestsHeader(requests: HttpRequest*) extends InternalCustomHeader("x-http2-push-requests") {
  requests.foreach(checkIfIsValidForPushing)

  /**
   * Checks that a given request is valid for pushing according to the rules from the spec:
   *
   *   Promised requests MUST be cacheable (see [RFC7231], Section 4.2.3),
   *   MUST be safe (see [RFC7231], Section 4.2.1), and MUST NOT include a
   *   request body.
   */
  private def checkIfIsValidForPushing(request: HttpRequest): Unit = {
    // TODO:
    require(request.method.isIdempotent, "Pushed requests must be idempotent (cacheable)") // TODO: check if that's a correct interpretation
    require(request.method.isSafe, "Pushed requests must be safe")
    require(request.entity.isKnownEmpty, "Pushed requests must not include a request body.")
  }
}
