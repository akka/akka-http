/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.server.directives

import java.util.function.{ Supplier, Function => JFunction }

import akka.http.impl.util.JavaMapping.Implicits._
import akka.http.javadsl.model.HttpResponse
import akka.http.javadsl.server.{ RequestContext, Route }
import akka.http.scaladsl.server.{ Directives => D }

abstract class AroundRequestDirectives extends FramedEntityStreamingDirectives {

  def aroundRequest(f: JFunction[RequestContext, JFunction[HttpResponse, HttpResponse]], inner: Supplier[Route]): Route = RouteAdapter {
    D.aroundRequest(rq => f.apply(RequestContext.wrap(rq)).apply(_).asScala) { inner.get.delegate }
  }
}
