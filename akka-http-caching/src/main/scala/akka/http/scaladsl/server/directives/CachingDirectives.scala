package akka.http.scaladsl.server.directives

import akka.actor.ActorRefFactory
import akka.http.caching.{ Cache, LfuCache }
import akka.http.scaladsl.server.Directive0

import scala.concurrent.duration.Duration
import scala.concurrent.{ ExecutionContext, Future }
import akka.http.scaladsl.server._
import akka.http.scaladsl.model.HttpMethods.GET
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.model.headers.CacheDirectives._
import akka.http.scaladsl.server.RouteResult.Rejected

trait CachingDirectives {
  import akka.http.scaladsl.server.directives.BasicDirectives._
  import akka.http.scaladsl.server.directives.RouteDirectives._

  /**
   * Wraps its inner Route with caching support using the given [[akka.http.caching.Cache]] implementation and
   * the in-scope keyer function.
   */
  def cache(csm: CacheSpecMagnet): Directive0 = cachingProhibited | alwaysCache(csm)

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
   * in-scope keyer function. Note that routes producing streaming responses cannot be wrapped with this directive.
   */
  def alwaysCache(csm: CacheSpecMagnet): Directive0 = {
    import csm._
    mapInnerRoute { route ⇒ ctx ⇒
      liftedKeyer(ctx) match {
        case Some(key) ⇒ responseCache.apply(key, () ⇒ route(ctx))
        case None      ⇒ route(ctx)
      }
    }
  }

  //LfuCache requires a loader function on creation - this will not be used.
  private val defaultLoader = (k: Any) ⇒ Future.successful(Rejected(Nil))

  //# route-Cache
  def routeCache(maxCapacity: Int = 500, initialCapacity: Int = 16, timeToLive: Duration = Duration.Inf,
                 timeToIdle: Duration = Duration.Inf): Cache[RouteResult] = {
    LfuCache(defaultLoader, maxCapacity, initialCapacity, timeToLive, timeToIdle)
  }
  //#
}

object CachingDirectives extends CachingDirectives

trait CacheSpecMagnet {
  def responseCache: Cache[RouteResult]
  def liftedKeyer: RequestContext ⇒ Option[Any]
  implicit def executionContext: ExecutionContext
}

object CacheSpecMagnet {
  implicit def apply(cache: Cache[RouteResult])(implicit keyer: CacheKeyer, factory: ActorRefFactory) = // # CacheSpecMagnet
    new CacheSpecMagnet {
      def responseCache = cache
      def liftedKeyer = keyer.lift
      implicit def executionContext = factory.dispatcher
    }
}

trait CacheKeyer extends (PartialFunction[RequestContext, Any])

object CacheKeyer {
  implicit val Default: CacheKeyer = CacheKeyer {
    case r: akka.http.scaladsl.server.RequestContext if r.request.method == GET ⇒ r.request.uri
  }

  def apply(f: PartialFunction[RequestContext, Any]) = new CacheKeyer {
    def isDefinedAt(ctx: RequestContext) = f.isDefinedAt(ctx)
    def apply(ctx: RequestContext) = f(ctx)
  }
}
