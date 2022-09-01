/*
 * Copyright (C) 2017-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.caching

import java.util.concurrent.{ CompletableFuture, Executor, TimeUnit }
import java.util.function.BiFunction
import akka.actor.ActorSystem
import akka.annotation.{ ApiMayChange, InternalApi }

import scala.concurrent.duration.Duration
import scala.concurrent.Future
import com.github.benmanes.caffeine.cache.{ AsyncCacheLoader, AsyncLoadingCache, Caffeine }
import akka.http.impl.util.JavaMapping.Implicits._
import akka.http.caching.CacheJavaMapping.Implicits._
import akka.http.caching.impl.{ CacheImpl, LoadingCacheImpl }
import akka.util.JavaDurationConverters.ScalaDurationOps

import scala.compat.java8.FutureConverters._
import scala.compat.java8.FunctionConverters._

@ApiMayChange
object LoadingCache {

  def apply[K, V](implicit system: ActorSystem, cacheLoader: AsyncCacheLoader[K, V]): LoadingCache[K, V] =
    apply(scaladsl.CachingSettings(system), cacheLoader)

  /**
   * Creates a new [[akka.http.caching.LoadingCache]], with an asynchronous refresh interval
   * and an optional expiration on unused entries
   */
  def apply[K, V](cachingSettings: scaladsl.CachingSettings, cacheLoader: AsyncCacheLoader[K, V]): LoadingCache[K, V] = {
    val settings = cachingSettings.loadingCache

    require(settings.maxCapacity >= 0, "maxCapacity must not be negative")
    require(settings.refreshAfterWrite.isFinite, "refreshAfterWrite must be finite")

    if (settings.expireAfterWrite.isFinite) expiringLoadingCache(cacheLoader, settings.maxCapacity, settings.refreshAfterWrite, settings.expireAfterWrite)
    else simpleLoadingCache(cacheLoader, settings.maxCapacity, settings.refreshAfterWrite)
  }

  /**
   * Java API
   * Creates a new [[akka.http.caching.LoadingCache]] using configuration of the system,
   * with an asynchronous refresh interval and an optional expiration on unused entries
   */
  def create[K, V](system: ActorSystem, cacheLoader: AsyncCacheLoader[K, V]): LoadingCache[K, V] =
    apply(system, cacheLoader)

  /**
   * Java API
   * Creates a new [[akka.http.caching.LoadingCache]], with an asynchronous refresh interval
   * and an optional expiration on unused entries
   */
  def create[K, V](settings: javadsl.CachingSettings, cacheLoader: AsyncCacheLoader[K, V]): LoadingCache[K, V] =
    apply(settings.asScala, cacheLoader)

  private def simpleLoadingCache[K, V](cacheLoader: AsyncCacheLoader[K, V], maxCapacity: Int, refreshAfterWrite: Duration): LoadingCache[K, V] = {
    val store = Caffeine.newBuilder().asInstanceOf[Caffeine[K, V]]
      .maximumSize(maxCapacity)
      .refreshAfterWrite(refreshAfterWrite.asJava)
      .buildAsync[K, V](cacheLoader)
    new LoadingCache[K, V](store)
  }

  private def expiringLoadingCache[K, V](cacheLoader: AsyncCacheLoader[K, V], maxCapacity: Long, refreshAfterWrite: Duration, expireAfterWrite: Duration): LoadingCache[K, V] = {

    def expire: Caffeine[K, V] => Caffeine[K, V] = { builder =>
      if (expireAfterWrite.isFinite) builder.expireAfterWrite(expireAfterWrite.toMillis, TimeUnit.MILLISECONDS)
      else builder
    }

    def refresh: Caffeine[K, V] => Caffeine[K, V] = { builder =>
      if (refreshAfterWrite.isFinite) builder.refreshAfterWrite(refreshAfterWrite.toMillis, TimeUnit.MILLISECONDS)
      else builder
    }

    val builder = Caffeine.newBuilder().asInstanceOf[Caffeine[K, V]]
      .maximumSize(maxCapacity)

    val store: AsyncLoadingCache[K, V] = (refresh andThen expire)(builder).buildAsync[K, V](cacheLoader)
    new LoadingCache[K, V](store)
  }

  def toJavaMappingFunction[K, V](genValue: () => Future[V]): BiFunction[K, Executor, CompletableFuture[V]] =
    asJavaBiFunction[K, Executor, CompletableFuture[V]]((k, e) => genValue().toJava.toCompletableFuture)

  def toJavaMappingFunction[K, V](loadValue: K => Future[V]): BiFunction[K, Executor, CompletableFuture[V]] =
    asJavaBiFunction[K, Executor, CompletableFuture[V]]((k, e) => loadValue(k).toJava.toCompletableFuture)
}

/** INTERNAL API */
@InternalApi
private[caching] class LoadingCache[K, V](store: AsyncLoadingCache[K, V]) extends LoadingCacheImpl[K, V](store)
