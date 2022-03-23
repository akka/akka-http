/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.client

import akka.annotation.InternalApi
import akka.http.scaladsl.model._

import scala.concurrent.Promise
import scala.util.Try

/** Internal API */
@InternalApi
private[client] object PoolFlow {

  case class RequestContext(request: HttpRequest, responsePromise: Promise[HttpResponse], retriesLeft: Int) {
    require(retriesLeft >= 0)

    def canBeRetried: Boolean = retriesLeft > 0
  }
  case class ResponseContext(rc: RequestContext, response: Try[HttpResponse])
}
