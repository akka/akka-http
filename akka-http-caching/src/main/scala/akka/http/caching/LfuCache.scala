/*
 * Copyright (C) 2017-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.caching

import java.util.concurrent.{ CompletableFuture, Executor, TimeUnit }
import java.util.function.BiFunction

import akka.actor.ActorSystem
import akka.annotation.{ ApiMayChange, InternalApi }

import scala.concurrent.duration.Duration
import scala.concurrent.{ ExecutionContext, Future }
import scala.jdk.CollectionConverters._
import com.github.benmanes.caffeine.cache.{ AsyncCache, Caffeine }
import akka.http.caching.LfuCache.toJavaMappingFunction
import akka.http.caching.scaladsl.Cache
import akka.http.impl.util.JavaMapping.Implicits._
import akka.http.caching.CacheJavaMapping.Implicits._

import scala.jdk.FunctionConverters._
import scala.jdk.FutureConverters._

@ApiMayChange
object LfuCache {

  def apply[K, V](implicit system: ActorSystem): akka.http.caching.scaladsl.Cache[K, V] =
    apply(scaladsl.CachingSettings(system))

  /**
   * Creates a new [[akka.http.caching.LfuCache]], with optional expiration depending
   * on whether a non-zero and finite timeToLive and/or timeToIdle is set or not.
   */
  def apply[K, V](cachingSettings: scaladsl.CachingSettings): akka.http.caching.scaladsl.Cache[K, V] = {
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
  def create[K, V](system: ActorSystem): akka.http.caching.javadsl.Cache[K, V] =
    apply(system)

  /**
   * Java API
   * Creates a new [[akka.http.caching.LfuCache]], with optional expiration depending
   * on whether a non-zero and finite timeToLive and/or timeToIdle is set or not.
   */
  def create[K, V](settings: javadsl.CachingSettings): akka.http.caching.javadsl.Cache[K, V] =
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
    ((k: K, e: Executor) => genValue().asJava.toCompletableFuture).asJavaBiFunction

  def toJavaMappingFunction[K, V](loadValue: K => Future[V]): BiFunction[K, Executor, CompletableFuture[V]] =
    ((k: K, e: Executor) => loadValue(k).asJava.toCompletableFuture).asJavaBiFunction
}

/** INTERNAL API */
@InternalApi
private[caching] class LfuCache[K, V](val store: AsyncCache[K, V]) extends Cache[K, V] {

  def get(key: K): Option[Future[V]] = Option(store.getIfPresent(key)).map(_.asScala)

  def apply(key: K, genValue: () => Future[V]): Future[V] = store.get(key, toJavaMappingFunction[K, V](genValue)).asScala

  /**
   * Multiple call to put method for the same key may result in a race condition,
   * the value yield by the last successful future for that key will replace any previously cached value.
   */
  def put(key: K, mayBeValue: Future[V])(implicit ex: ExecutionContext): Future[V] = {
    val previouslyCacheValue = Option(store.getIfPresent(key))

    previouslyCacheValue match {
      case None =>
        store.put(key, mayBeValue.asJava.toCompletableFuture)
        mayBeValue
      case _ => mayBeValue.map { value =>
        store.put(key, Future.successful(value).asJava.toCompletableFuture)
        value
      }
    }
  }

  def getOrLoad(key: K, loadValue: K => Future[V]): Future[V] = store.get(key, toJavaMappingFunction[K, V](loadValue)).asScala

  def remove(key: K): Unit = store.synchronous().invalidate(key)

  def clear(): Unit = store.synchronous().invalidateAll()

  def keys: Set[K] = store.synchronous().asMap().keySet().asScala.toSet

  override def size: Int = store.synchronous().asMap().size()
}
