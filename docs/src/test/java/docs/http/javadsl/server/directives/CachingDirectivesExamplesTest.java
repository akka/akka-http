/*
 * Copyright (C) 2016-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.javadsl.server.directives;

import java.util.concurrent.atomic.AtomicInteger;
import akka.japi.JavaPartialFunction;
import akka.http.javadsl.model.Uri;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpMethods;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.model.headers.*;
import akka.http.javadsl.model.headers.CacheDirectives.*;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.server.RequestContext;
//#caching-directives-import
import static akka.http.javadsl.server.directives.CachingDirectives.*;
//#caching-directives-import
import scala.concurrent.duration.Duration;
import java.util.concurrent.TimeUnit;
import akka.http.javadsl.testkit.JUnitRouteTest;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

import akka.http.javadsl.server.RouteResult;
//#create-cache-imports
import akka.http.caching.javadsl.Cache;
import akka.http.caching.javadsl.CachingSettings;
import akka.http.caching.javadsl.LfuCacheSettings;
import akka.http.caching.LfuCache;
//#create-cache-imports

//#cache
import static akka.http.javadsl.server.Directives.complete;
import static akka.http.javadsl.server.Directives.extractUri;
import static akka.http.javadsl.server.Directives.path;
import static akka.http.javadsl.server.PathMatchers.segment;
//#cache

public class CachingDirectivesExamplesTest extends JUnitRouteTest {

  @Test
  public void testCache() {
    //#cache
    final CachingSettings cachingSettings = CachingSettings.create(system());
    final JavaPartialFunction<RequestContext, Uri> simpleKeyer = new JavaPartialFunction<RequestContext, Uri>() {
      public Uri apply(RequestContext in, boolean isCheck) {
        final HttpRequest request = in.getRequest();
        final boolean isGet = request.method() == HttpMethods.GET;
        final boolean isAuthorized = request.getHeader(Authorization.class).isPresent();

        if (isGet && !isAuthorized)
          return request.getUri();
        else
          throw noMatch();
      }
    };

    // Created outside the route to allow using
    // the same cache across multiple calls
    final Cache<Uri, RouteResult> myCache = routeCache(cachingSettings);

    final AtomicInteger count = new AtomicInteger(0);
    final Route route = path(segment("cached"), () ->
      cache(myCache, simpleKeyer, () ->
        extractUri(uri ->
          complete(String.format("Request for %s @ count %d", uri, count.incrementAndGet()))
        )
      )
    );

    // tests:
    testRoute(route)
      .run(HttpRequest.GET("/cached"))
      .assertEntity("Request for http://example.com/cached @ count 1");

    // now cached
    testRoute(route)
      .run(HttpRequest.GET("/cached"))
      .assertEntity("Request for http://example.com/cached @ count 1");

    // caching prevented
    final CacheControl noCache = CacheControl.create(CacheDirectives.NO_CACHE);
    testRoute(route).run(HttpRequest.GET("/cached").addHeader(noCache))
      .assertEntity("Request for http://example.com/cached @ count 2");
    //#cache
  }

  @Test
  public void testAlwaysCache() {
    //#always-cache
    final CachingSettings cachingSettings = CachingSettings.create(system());
    // Example keyer for non-authenticated GET requests
    final JavaPartialFunction<RequestContext, Uri> simpleKeyer = new JavaPartialFunction<RequestContext, Uri>() {
      public Uri apply(RequestContext in, boolean isCheck) {
        final HttpRequest request = in.getRequest();
        final boolean isGet = request.method() == HttpMethods.GET;
        final boolean isAuthorized = request.getHeader(Authorization.class).isPresent();

        if (isGet && !isAuthorized)
          return request.getUri();
        else
          throw noMatch();
      }
    };

    // Created outside the route to allow using
    // the same cache across multiple calls
    final Cache<Uri, RouteResult> myCache = routeCache(cachingSettings);

    final AtomicInteger count = new AtomicInteger(0);
    final Route route = path("cached", () ->
        alwaysCache(myCache, simpleKeyer, () ->
        extractUri(uri ->
          complete(String.format("Request for %s @ count %d", uri, count.incrementAndGet()))
        )
      )
    );

    // tests:
    testRoute(route)
      .run(HttpRequest.GET("/cached"))
      .assertEntity("Request for http://example.com/cached @ count 1");

    // now cached
    testRoute(route)
      .run(HttpRequest.GET("/cached"))
      .assertEntity("Request for http://example.com/cached @ count 1");

    final CacheControl noCache = CacheControl.create(CacheDirectives.NO_CACHE);
    testRoute(route)
      .run(HttpRequest.GET("/cached").addHeader(noCache))
      .assertEntity("Request for http://example.com/cached @ count 1");
    //#always-cache
  }

  @Test
  public void testCachingProhibited() {
    //#caching-prohibited
    final Route route = cachingProhibited(() ->
      complete("abc")
    );

    // tests:
    testRoute(route)
      .run(HttpRequest.GET("/"))
      .assertStatusCode(StatusCodes.NOT_FOUND);

    final CacheControl noCache = CacheControl.create(CacheDirectives.NO_CACHE);
    testRoute(route)
      .run(HttpRequest.GET("/").addHeader(noCache))
      .assertEntity("abc");
    //#caching-prohibited
  }

  @Test
  public void testCreateCache() {
    //#keyer-function

    // Use the request's URI as the cache's key
    final JavaPartialFunction<RequestContext, Uri> keyerFunction = new JavaPartialFunction<RequestContext, Uri>() {
      public Uri apply(RequestContext in, boolean isCheck) {
        return in.getRequest().getUri();
      }
    };
    //#keyer-function

    final AtomicInteger count = new AtomicInteger(0);
    final Route innerRoute = extractUri(uri ->
      complete(String.format("Request for %s @ count %d", uri, count.incrementAndGet()))
    );

    //#create-cache
    final CachingSettings defaultCachingSettings = CachingSettings.create(system());
    final LfuCacheSettings lfuCacheSettings = defaultCachingSettings.lfuCacheSettings()
      .withInitialCapacity(25)
      .withMaxCapacity(50)
      .withTimeToLive(Duration.create(20, TimeUnit.SECONDS))
      .withTimeToIdle(Duration.create(10, TimeUnit.SECONDS));
    final CachingSettings cachingSettings = defaultCachingSettings.withLfuCacheSettings(lfuCacheSettings);
    final Cache<Uri, RouteResult> lfuCache = LfuCache.create(cachingSettings);

    // Create the route
    final Route route = cache(lfuCache, keyerFunction, () -> innerRoute);
    //#create-cache

    // We don't test the eviction settings here. Deterministic testing of eviction is hard because
    // caffeine's LFU is probabilistic.
  }
}
