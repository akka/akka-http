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
import com.github.benmanes.caffeine.cache.{ AsyncCache, Caffeine }
import akka.http.impl.util.JavaMapping.Implicits._
import akka.http.caching.CacheJavaMapping.Implicits._
import akka.http.caching.impl.CacheImpl

import scala.compat.java8.FutureConverters._
import scala.compat.java8.FunctionConverters._

@ApiMayChange
object LfuCache {

  def apply[K, V](implicit system: ActorSystem): LfuCache[K, V] =
    apply(scaladsl.CachingSettings(system))

  /**
   * Creates a new [[akka.http.caching.LfuCache]], with optional expiration depending
   * on whether a non-zero and finite timeToLive and/or timeToIdle is set or not.
   */
  def apply[K, V](cachingSettings: scaladsl.CachingSettings): LfuCache[K, V] = {
    val settings = cachingSettings.lfuCacheSettings

    require(settings.maxCapacity >= 0, "maxCapacity must not be negative")
    require(settings.initialCapacity <= settings.maxCapacity, "initialCapacity must be <= maxCapacity")

    if (settings.timeToLive.isFinite || settings.timeToIdle.isFinite) expiringLfuCache(settings.maxCapacity, settings.initialCapacity, settings.timeToLive, settings.timeToIdle)
    else simpleLfuCache(settings.maxCapacity, settings.initialCapacity)
  }

  /**
   * Java API
   * Creates a new [[akka.http.caching.LfuCache]] using configuration of the system,
   * with optional expiration depending on whether a non-zero and finite timeToLive and/or timeToIdle is set or not.
   */
  def create[K, V](system: ActorSystem): LfuCache[K, V] =
    apply(system)

  /**
   * Java API
   * Creates a new [[akka.http.caching.LfuCache]], with optional expiration depending
   * on whether a non-zero and finite timeToLive and/or timeToIdle is set or not.
   */
  def create[K, V](settings: javadsl.CachingSettings): LfuCache[K, V] =
    apply(settings.asScala)

  private def simpleLfuCache[K, V](maxCapacity: Int, initialCapacity: Int): LfuCache[K, V] = {
    val store = Caffeine.newBuilder().asInstanceOf[Caffeine[K, V]]
      .initialCapacity(initialCapacity)
      .maximumSize(maxCapacity)
      .buildAsync[K, V]
    new LfuCache[K, V](store)
  }

  private def expiringLfuCache[K, V](maxCapacity: Long, initialCapacity: Int,
                                     timeToLive: Duration, timeToIdle: Duration): LfuCache[K, V] = {
    require(
      !timeToLive.isFinite || !timeToIdle.isFinite || timeToLive >= timeToIdle,
      s"timeToLive($timeToLive) must be >= than timeToIdle($timeToIdle)")

    def ttl: Caffeine[K, V] => Caffeine[K, V] = { builder =>
      if (timeToLive.isFinite) builder.expireAfterWrite(timeToLive.toMillis, TimeUnit.MILLISECONDS)
      else builder
    }

    def tti: Caffeine[K, V] => Caffeine[K, V] = { builder =>
      if (timeToIdle.isFinite) builder.expireAfterAccess(timeToIdle.toMillis, TimeUnit.MILLISECONDS)
      else builder
    }

    val builder = Caffeine.newBuilder().asInstanceOf[Caffeine[K, V]]
      .initialCapacity(initialCapacity)
      .maximumSize(maxCapacity)

    val store = (ttl andThen tti)(builder).buildAsync[K, V]
    new LfuCache[K, V](store)
  }

  def toJavaMappingFunction[K, V](genValue: () => Future[V]): BiFunction[K, Executor, CompletableFuture[V]] =
    asJavaBiFunction[K, Executor, CompletableFuture[V]]((k, e) => genValue().toJava.toCompletableFuture)

  def toJavaMappingFunction[K, V](loadValue: K => Future[V]): BiFunction[K, Executor, CompletableFuture[V]] =
    asJavaBiFunction[K, Executor, CompletableFuture[V]]((k, e) => loadValue(k).toJava.toCompletableFuture)
}

/** INTERNAL API */
@InternalApi
private[caching] class LfuCache[K, V](store: AsyncCache[K, V]) extends CacheImpl[K, V](store)
