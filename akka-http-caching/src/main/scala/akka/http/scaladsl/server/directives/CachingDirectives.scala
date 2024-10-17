/*
 * Copyright (C) 2017-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.server.directives

import akka.actor.ActorSystem
import akka.annotation.ApiMayChange
import akka.http.caching.scaladsl.{ Cache, CachingSettings }
import akka.http.caching.LfuCache
import akka.http.scaladsl.server.Directive0
import akka.http.scaladsl.server._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.model.headers.CacheDirectives._

import scala.concurrent.Future

@ApiMayChange
trait CachingDirectives {
  import akka.http.scaladsl.server.directives.BasicDirectives._
  import akka.http.scaladsl.server.directives.RouteDirectives._

  /**
   * Wraps its inner Route with caching support using the given [[Cache]] implementation and
   * keyer function.
   */
  def cache[K](cache: Cache[K, RouteResult], keyer: PartialFunction[RequestContext, K]): Directive0 =
    cachingProhibited | alwaysCache(cache, keyer)

  /**
   * Passes only requests to the inner route that explicitly forbid caching with a `Cache-Control` header with either
   * a `no-cache` or `max-age=0` setting.
   */
  def cachingProhibited: Directive0 =
    extract(_.request.headers.exists {
      case x: `Cache-Control` => x.directives.exists {
        case `no-cache`   => true
        case `max-age`(0) => true
        case _            => false
      }
      case _ => false
    }).flatMap(if (_) pass else reject)

  /**
   * Wraps its inner Route with caching support using the given [[Cache]] implementation and
   * keyer function. Note that routes producing streaming responses cannot be wrapped with this directive.
   */
  def alwaysCache[K](cache: Cache[K, RouteResult], keyer: PartialFunction[RequestContext, K]): Directive0 =
    // Do directive processing asynchronously to avoid locking the cache accidentally (#4092)
    // This will be slightly slower, but the rational here is that caching is used for slower kind of processing
    // anyway so the performance hit should be acceptable.
    Directive { inner => ctx =>
      import ctx.executionContext
      keyer.lift(ctx) match {
        case Some(key) => cache.apply(key, () => Future(inner(())(ctx)).flatten)
        case None      => inner(())(ctx)
      }
    }

  /**
   * Creates an [[LfuCache]] with default settings obtained from the system's configuration.
   */
  def routeCache[K](implicit s: ActorSystem): Cache[K, RouteResult] =
    LfuCache[K, RouteResult](s)

  /**
   * Creates an [[LfuCache]].
   */
  def routeCache[K](settings: CachingSettings): Cache[K, RouteResult] =
    LfuCache[K, RouteResult](settings)
}

object CachingDirectives extends CachingDirectives
