package akka.http.scaladsl.server.directives

import akka.annotation.ApiMayChange
import akka.http.caching.{ Cache, LfuCache, LfuCacheSettings }
import akka.http.scaladsl.server.Directive0

import scala.concurrent.duration.Duration
import akka.http.scaladsl.server._
import akka.http.scaladsl.model.HttpMethods.GET
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.model.headers.CacheDirectives._

@ApiMayChange
trait CachingDirectives {
  import akka.http.scaladsl.server.directives.BasicDirectives._
  import akka.http.scaladsl.server.directives.RouteDirectives._

  /**
   * Wraps its inner Route with caching support using the given [[akka.http.caching.Cache]] implementation and
   * keyer function.
   */
  def cache[K](cache: Cache[K, RouteResult], keyer: PartialFunction[RequestContext, K]): Directive0 =
    cachingProhibited | alwaysCache(cache, keyer)

  /**
   * A simple keyer function that will cache responses to *all* GET requests, with the URI as key.
   * WARNING - consider whether you need special handling for e.g. authorised requests.
   */
  val simpleKeyer: PartialFunction[RequestContext, Uri] = {
    case r: RequestContext if r.request.method == GET ⇒ r.request.uri
  }

  /**
   * Passes only requests to the inner route that explicitly forbid caching with a `Cache-Control` header with either
   * a `no-cache` or `max-age=0` setting.
   */
  def cachingProhibited: Directive0 =
    extract(_.request.headers.exists {
      case x: `Cache-Control` ⇒ x.directives.exists {
        case `no-cache`   ⇒ true
        case `max-age`(0) ⇒ true
        case _            ⇒ false
      }
      case _ ⇒ false
    }).flatMap(if (_) pass else reject)

  /**
   * Wraps its inner Route with caching support using the given [[akka.http.caching.Cache]] implementation and
   * keyer function. Note that routes producing streaming responses cannot be wrapped with this directive.
   */
  def alwaysCache[K](cache: Cache[K, RouteResult], keyer: PartialFunction[RequestContext, K]): Directive0 = {
    mapInnerRoute { route ⇒ ctx ⇒
      keyer.lift(ctx) match {
        case Some(key) ⇒ cache.apply(key, () ⇒ route(ctx))
        case None      ⇒ route(ctx)
      }
    }
  }

  //# route-Cache
  def routeCache[K](maxCapacity: Int = 500, initialCapacity: Int = 16, timeToLive: Duration = Duration.Inf,
                    timeToIdle: Duration = Duration.Inf): Cache[K, RouteResult] = {
    LfuCache {
      LfuCacheSettings()
        .withMaxCapacity(maxCapacity)
        .withInitialCapacity(initialCapacity)
        .withTimeToLive(timeToLive)
        .withTimeToIdle(timeToIdle)
    }
  }
  //#
}
