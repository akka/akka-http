/*
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com/>
 */

package akka.http.javadsl.server.directives

import java.util.function.Supplier

import akka.annotation.ApiMayChange
import akka.http.caching.javadsl.Cache
import akka.http.javadsl.model.Uri
import akka.http.javadsl.server.{ RequestContext, Route, RouteResult }
import akka.http.javadsl.model.HttpMethods.GET

import scala.concurrent.duration.Duration

@ApiMayChange
class CachingDirectives {

  import akka.http.scaladsl.server.directives.{ CachingDirectives ⇒ D }

  /**
   * Wraps its inner Route with caching support using the given [[akka.http.caching.scaladsl.Cache]] implementation and
   * keyer function.
   *
   * Use [[akka.japi.JavaPartialFunction]] to build the `keyer`.
   */
  def cache[K](cache: Cache[K, RouteResult], keyer: PartialFunction[RequestContext, K], inner: Supplier[Route]) = RouteAdapter {
    D.cache(
      cache.asInstanceOf[akka.http.caching.scaladsl.Cache[K, akka.http.scaladsl.server.RouteResult]],
      toScalaKeyer(keyer)
    ) { inner.get.delegate }
  }

  private def toScalaKeyer[K](keyer: PartialFunction[RequestContext, K]): PartialFunction[akka.http.scaladsl.server.RequestContext, K] = {
    PartialFunction {
      (scalaRequestContext: akka.http.scaladsl.server.RequestContext) ⇒
        {
          val javaRequestContext = akka.http.javadsl.server.RoutingJavaMapping.RequestContext.toJava(scalaRequestContext)
          keyer(javaRequestContext)
        }
    }
  }

  /**
   * A simple keyer function that will cache responses to *all* GET requests, with the URI as key.
   * WARNING - consider whether you need special handling for e.g. authorised requests.
   */
  val simpleKeyer: PartialFunction[RequestContext, Uri] = {
    case r: RequestContext if r.getRequest.method == GET ⇒ r.getRequest.getUri
  }

  /**
   * Passes only requests to the inner route that explicitly forbid caching with a `Cache-Control` header with either
   * a `no-cache` or `max-age=0` setting.
   */
  def cachingProhibited(inner: Supplier[Route]) = RouteAdapter {
    D.cachingProhibited { inner.get.delegate }
  }

  /**
   * Wraps its inner Route with caching support using the given [[Cache]] implementation and
   * keyer function. Note that routes producing streaming responses cannot be wrapped with this directive.
   */
  def alwaysCache[K](cache: Cache[K, RouteResult], keyer: PartialFunction[RequestContext, K], inner: Supplier[Route]) = RouteAdapter {
    D.alwaysCache(
      cache.asInstanceOf[akka.http.caching.scaladsl.Cache[K, akka.http.scaladsl.server.RouteResult]],
      toScalaKeyer(keyer)
    ) { inner.get.delegate }
  }

  def routeCache[K](): Cache[K, RouteResult] =
    D.routeCache(500, 16, Duration.Inf, Duration.Inf)

  def routeCache[K](maxCapacity: Int): Cache[K, RouteResult] =
    D.routeCache(maxCapacity, 16, Duration.Inf, Duration.Inf)

  def routeCache[K](maxCapacity: Int, initialCapacity: Int): Cache[K, RouteResult] =
    D.routeCache(maxCapacity, initialCapacity, Duration.Inf, Duration.Inf)

  def routeCache[K](maxCapacity: Int, initialCapacity: Int, timeToLive: Duration): Cache[K, RouteResult] =
    D.routeCache(maxCapacity, initialCapacity, timeToLive, Duration.Inf)

  def routeCache[K](maxCapacity: Int, initialCapacity: Int, timeToLive: Duration, timeToIdle: Duration): Cache[K, RouteResult] =
    D.routeCache(maxCapacity, initialCapacity, timeToLive, timeToIdle)
}

object CachingDirectives extends CachingDirectives
