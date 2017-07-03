/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.http.caching

import java.util.concurrent.{ CompletableFuture, Executor, TimeUnit }

import akka.annotation.ApiMayChange

import scala.collection.JavaConverters._
import scala.concurrent.duration.Duration
import scala.concurrent.Future
import com.github.benmanes.caffeine.cache.{ AsyncCacheLoader, AsyncLoadingCache, Caffeine }
import akka.http.caching.LfuCache.toJavaMappingFunction
import akka.http.caching.LfuCacheSettings.LfuCacheSettingsImpl
import akka.http.caching.scaladsl.Cache

import scala.compat.java8.FutureConverters._
import scala.compat.java8.FunctionConverters._

@ApiMayChange
object LfuCache {

  /**
   * Creates a new [[akka.http.caching.LfuCache]], with optional expiration depending
   * on whether a non-zero and finite timeToLive and/or timeToIdle is set or not.
   */
  def apply[K, V](settings: LfuCacheSettings = LfuCacheSettings()): akka.http.caching.scaladsl.Cache[K, V] = {

    require(settings.maxCapacity >= 0, "maxCapacity must not be negative")
    require(settings.initialCapacity <= settings.maxCapacity, "initialCapacity must be <= maxCapacity")

    if (settings.timeToLive.isFinite() || settings.timeToIdle.isFinite()) expiringLfuCache(settings.maxCapacity, settings.initialCapacity, settings.timeToLive, settings.timeToIdle)
    else simpleLfuCache(settings.maxCapacity, settings.initialCapacity)
  }

  /**
   * Creates a new [[akka.http.caching.LfuCache]], with optional expiration depending
   * on whether a non-zero and finite timeToLive and/or timeToIdle is set or not.
   */
  def create[K, V](): akka.http.caching.javadsl.Cache[K, V] = 
    apply(LfuCacheSettings())
  
  /**
   * Creates a new [[akka.http.caching.LfuCache]], with optional expiration depending
   * on whether a non-zero and finite timeToLive and/or timeToIdle is set or not.
   */
  def create[K, V](settings: LfuCacheSettings): akka.http.caching.javadsl.Cache[K, V] = 
    apply(settings)

  private def simpleLfuCache[K, V](maxCapacity: Int, initialCapacity: Int): LfuCache[K, V] = {
    val store = Caffeine.newBuilder().asInstanceOf[Caffeine[K, V]]
      .initialCapacity(initialCapacity)
      .maximumSize(maxCapacity)
      .buildAsync[K, V](dummyLoader[K, V])
    new LfuCache[K, V](store)
  }

  private def expiringLfuCache[K, V](maxCapacity: Long, initialCapacity: Int,
                                     timeToLive: Duration, timeToIdle: Duration): LfuCache[K, V] = {
    require(
      !timeToLive.isFinite || !timeToIdle.isFinite || timeToLive > timeToIdle,
      s"timeToLive($timeToLive) must be greater than timeToIdle($timeToIdle)")

    def ttl: Caffeine[K, V] ⇒ Caffeine[K, V] = { builder ⇒
      if (timeToLive.isFinite) builder.expireAfterWrite(timeToLive.toMillis, TimeUnit.MILLISECONDS)
      else builder
    }

    def tti: Caffeine[K, V] ⇒ Caffeine[K, V] = { builder ⇒
      if (timeToIdle.isFinite) builder.expireAfterAccess(timeToIdle.toMillis, TimeUnit.MILLISECONDS)
      else builder
    }

    val builder = Caffeine.newBuilder().asInstanceOf[Caffeine[K, V]]
      .initialCapacity(initialCapacity)
      .maximumSize(maxCapacity)

    val store = (ttl andThen tti)(builder).buildAsync[K, V](dummyLoader[K, V])
    new LfuCache[K, V](store)
  }

  //LfuCache requires a loader function on creation - this will not be used.
  private def dummyLoader[K, V] = new AsyncCacheLoader[K, V] {
    def asyncLoad(k: K, e: Executor) =
      Future.failed[V](new RuntimeException("Dummy loader should not be used by LfuCache")).toJava.toCompletableFuture
  }

  def toJavaMappingFunction[K, V](genValue: () ⇒ Future[V]) =
    asJavaBiFunction[K, Executor, CompletableFuture[V]]((k, e) ⇒ genValue().toJava.toCompletableFuture)
}

private[caching] class LfuCache[K, V](val store: AsyncLoadingCache[K, V]) extends Cache[K, V] {

  def get(key: K): Option[Future[V]] = Option(store.getIfPresent(key)).map(_.toScala)

  def apply(key: K, genValue: () ⇒ Future[V]): Future[V] = store.get(key, toJavaMappingFunction[K, V](genValue)).toScala

  def remove(key: K): Unit = store.synchronous().invalidate(key)

  def clear(): Unit = store.synchronous().invalidateAll()

  def keys: Set[K] = store.synchronous().asMap().keySet().asScala.toSet

  override def size: Int = store.synchronous().asMap().size()
}

abstract class LfuCacheSettings private[http] () { self: LfuCacheSettingsImpl ⇒
  def maxCapacity: Int
  def initialCapacity: Int
  def timeToLive: Duration
  def timeToIdle: Duration

  def withMaxCapacity(newMaxCapacity: Int): LfuCacheSettings = copy(maxCapacity = newMaxCapacity)
  def withInitialCapacity(newInitialCapacity: Int): LfuCacheSettings = copy(initialCapacity = newInitialCapacity)
  def withTimeToLive(newTimeToLive: Duration): LfuCacheSettings = copy(timeToLive = newTimeToLive)
  def withTimeToIdle(newTimeToIdle: Duration): LfuCacheSettings = copy(timeToIdle = newTimeToIdle)
}

object LfuCacheSettings {
  def apply(): LfuCacheSettings = LfuCacheSettingsImpl()

  private[http] case class LfuCacheSettingsImpl(
    maxCapacity:     Int      = 500,
    initialCapacity: Int      = 16,
    timeToLive:      Duration = Duration.Inf,
    timeToIdle:      Duration = Duration.Inf
  ) extends LfuCacheSettings
}
