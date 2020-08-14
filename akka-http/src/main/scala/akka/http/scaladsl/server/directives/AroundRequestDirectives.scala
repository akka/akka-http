package akka.http.scaladsl.server.directives

import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.headers.OnComplete
import akka.http.scaladsl.server.{ Directive, Directive0, RequestContext }

trait AroundRequestDirectives {

  def aroundRequest(onRequest: RequestContext => HttpResponse => HttpResponse): Directive0 =
    Directive { inner => ctx =>
      ctx.request.header[OnComplete] match {
        case Some(och) =>
          val onComplete = onRequest(ctx)
          och.onCompleteAccess.add(onComplete)
        case _ =>
          ctx.log.warning("aroundRequest was used in route however no OnComplete is set!")
      }
      inner(())(ctx)
    }
}
