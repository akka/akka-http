/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.server.directives

import akka.http.scaladsl.model.{ AttributeKeys, HttpResponse, OnCompleteAccess }
import akka.http.scaladsl.server.{ Directive, Directive0, RequestContext }

trait AroundRequestDirectives {

  def aroundRequest(onRequest: RequestContext => HttpResponse => HttpResponse): Directive0 =
    Directive { inner => ctx =>
      ctx.request.attribute[OnCompleteAccess](AttributeKeys.onCompleteAccess) match {
        case Some(oca) =>
          val onComplete = onRequest(ctx)
          oca.add(onComplete)
        case _ =>
          ctx.log.warning("aroundRequest directive was used however no OnComplete attribute is set! Check akka.http.server.around-request config.")
      }
      inner(())(ctx)
    }
}
