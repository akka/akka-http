/*
 * Copyright (C) 2017-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.caching

import java.util.concurrent.{CompletableFuture, Executor, TimeUnit}
import java.util.function.BiFunction
import akka.actor.ActorSystem
import akka.annotation.{ApiMayChange, InternalApi}

import scala.collection.JavaConverters._
import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}
import com.github.benmanes.caffeine.cache.{AsyncCache, AsyncLoadingCache, Caffeine}
import akka.http.caching.LfuCache.toJavaMappingFunction
import akka.http.caching.scaladsl.Cache
import akka.http.impl.util.JavaMapping.Implicits._
import akka.http.caching.CacheJavaMapping.Implicits._
import akka.util.JavaDurationConverters.ScalaDurationOps

import scala.compat.java8.FutureConverters._
import scala.compat.java8.FunctionConverters._

@ApiMayChange
object RefreshingCache {

  def apply[K, V](implicit system: ActorSystem): akka.http.caching.scaladsl.Cache[K, V] =
    apply(scaladsl.CachingSettings(system))

  /**
   * Creates a new [[akka.http.caching.RefreshingCache]], with an asychronous refresh interval
   * and an optional expiration on unused entries
   */
  def apply[K, V](cachingSettings: scaladsl.CachingSettings): akka.http.caching.scaladsl.Cache[K, V] = {
    val settings = cachingSettings.refreshingCacheSettings

    require(settings.maxCapacity >= 0, "maxCapacity must not be negative")
    require(settings.refreshAfterWrite.isFinite, "refreshAfterWrite must be finite")

    if (settings.expireAfterWrite.isFinite) expiringRefreshingCache(settings.maxCapacity, settings.refreshAfterWrite, settings.expireAfterWrite)
    else simpleRefreshingCache(settings.maxCapacity, settings.refreshAfterWrite)
  }

  /**
   * Java API
   * Creates a new [[akka.http.caching.RefreshingCache]] using configuration of the system,
   * with an asychronous refresh interval and an optional expiration on unused entries
   */
  def create[K, V](system: ActorSystem): akka.http.caching.javadsl.Cache[K, V] =
    apply(system)

  /**
   * Java API
   * Creates a new [[akka.http.caching.RefreshingCache]], with an asychronous refresh interval
   * and an optional expiration on unused entries
   */
  def create[K, V](settings: javadsl.CachingSettings): akka.http.caching.javadsl.Cache[K, V] =
    apply(settings.asScala)

  private def simpleRefreshingCache[K, V](maxCapacity: Int, refreshAfterWrite: Duration): RefreshingCache[K, V] = {
    val store = Caffeine.newBuilder().asInstanceOf[Caffeine[K, V]]
      .maximumSize(maxCapacity)
      .refreshAfterWrite(refreshAfterWrite.asJava)
      .buildAsync[K, V]
    new RefreshingCache[K, V](store)
  }

  private def expiringRefreshingCache[K, V](maxCapacity: Long, refreshAfterWrite: Duration, expireAfterWrite: Duration): RefreshingCache[K, V] = {

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

    val store = (refresh andThen expire)(builder).buildAsync[K, V]
    new RefreshingCache[K, V](store)
  }

  def toJavaMappingFunction[K, V](genValue: () => Future[V]): BiFunction[K, Executor, CompletableFuture[V]] =
    asJavaBiFunction[K, Executor, CompletableFuture[V]]((k, e) => genValue().toJava.toCompletableFuture)

  def toJavaMappingFunction[K, V](loadValue: K => Future[V]): BiFunction[K, Executor, CompletableFuture[V]] =
    asJavaBiFunction[K, Executor, CompletableFuture[V]]((k, e) => loadValue(k).toJava.toCompletableFuture)
}

/** INTERNAL API */
@InternalApi
private[caching] class RefreshingCache[K, V](val store: AsyncLoadingCache[K, V]) extends Cache[K, V] {

  def get(key: K): Option[Future[V]] = Option(store.getIfPresent(key)).map(_.toScala)

  def apply(key: K, genValue: () => Future[V]): Future[V] = store.get(key, toJavaMappingFunction[K, V](genValue)).toScala

  /**
   * Multiple call to put method for the same key may result in a race condition,
   * the value yield by the last successful future for that key will replace any previously cached value.
   */
  def put(key: K, mayBeValue: Future[V])(implicit ex: ExecutionContext): Future[V] = {
    val previouslyCacheValue = Option(store.getIfPresent(key))

    previouslyCacheValue match {
      case None =>
        store.put(key, toJava(mayBeValue).toCompletableFuture)
        mayBeValue
      case _ => mayBeValue.map { value =>
        store.put(key, toJava(Future.successful(value)).toCompletableFuture)
        value
      }
    }
  }

  def getOrLoad(key: K, loadValue: K => Future[V]): Future[V] = store.get(key, toJavaMappingFunction[K, V](loadValue)).toScala

  def remove(key: K): Unit = store.synchronous().invalidate(key)

  def clear(): Unit = store.synchronous().invalidateAll()

  def keys: Set[K] = store.synchronous().asMap().keySet().asScala.toSet

  override def size: Int = store.synchronous().asMap().size()
}
