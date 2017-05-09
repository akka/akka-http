package akka.http.caching

import java.util.concurrent.{ CompletableFuture, Executor, TimeUnit }

import scala.collection.JavaConverters._
import scala.concurrent.duration.Duration
import scala.concurrent.Future
import com.github.benmanes.caffeine.cache.{ AsyncCacheLoader, AsyncLoadingCache, Caffeine }
import akka.http.caching.LfuCache.toJavaMappingFunction

import scala.compat.java8.FutureConverters._
import scala.compat.java8.FunctionConverters._

object LfuCache {

  /**
   * Creates a new [[akka.http.caching.LfuCache]], with optional expiration depending
   * on whether a non-zero and finite timeToLive and/or timeToIdle is set or not.
   */
  def apply[V](
    maxCapacity:     Int      = 500,
    initialCapacity: Int      = 16,
    timeToLive:      Duration = Duration.Inf,
    timeToIdle:      Duration = Duration.Inf): LfuCache[V] = {

    require(maxCapacity >= 0, "maxCapacity must not be negative")
    require(initialCapacity <= maxCapacity, "initialCapacity must be <= maxCapacity")

    if (timeToLive.isFinite() || timeToIdle.isFinite()) expiringLfuCache(maxCapacity, initialCapacity, timeToLive, timeToIdle)
    else simpleLfuCache(maxCapacity, initialCapacity)
  }

  private def simpleLfuCache[V](maxCapacity: Int, initialCapacity: Int): LfuCache[V] = {
    val store = Caffeine.newBuilder().asInstanceOf[Caffeine[Any, V]]
      .initialCapacity(initialCapacity)
      .maximumSize(maxCapacity)
      .buildAsync[Any, V](dummyLoader[V])
    new LfuCache[V](store)
  }

  private def expiringLfuCache[V](maxCapacity: Long, initialCapacity: Int,
                                  timeToLive: Duration, timeToIdle: Duration): LfuCache[V] = {
    require(
      !timeToLive.isFinite || !timeToIdle.isFinite || timeToLive > timeToIdle,
      s"timeToLive($timeToLive) must be greater than timeToIdle($timeToIdle)")

    def ttl: Caffeine[Any, V] ⇒ Caffeine[Any, V] = { builder ⇒
      if (timeToLive.isFinite) builder.expireAfterWrite(timeToLive.toMillis, TimeUnit.MILLISECONDS)
      else builder
    }

    def tti: Caffeine[Any, V] ⇒ Caffeine[Any, V] = { builder ⇒
      if (timeToIdle.isFinite) builder.expireAfterAccess(timeToIdle.toMillis, TimeUnit.MILLISECONDS)
      else builder
    }

    val builder = Caffeine.newBuilder().asInstanceOf[Caffeine[Any, V]]
      .initialCapacity(initialCapacity)
      .maximumSize(maxCapacity)

    val store = (ttl andThen tti)(builder).buildAsync[Any, V](dummyLoader[V])
    new LfuCache[V](store)
  }

  //LfuCache requires a loader function on creation - this will not be used.
  private def dummyLoader[V] = new AsyncCacheLoader[Any, V] {
    def asyncLoad(k: Any, e: Executor) =
      Future.failed[V](new RuntimeException("Dummy loader should not be used by LfuCache")).toJava.toCompletableFuture
  }

  def toJavaMappingFunction[V](genValue: () ⇒ Future[V]) =
    asJavaBiFunction[Any, Executor, CompletableFuture[V]]((k, e) ⇒ genValue().toJava.toCompletableFuture)
}

private[caching] class LfuCache[V](val store: AsyncLoadingCache[Any, V]) extends Cache[V] {

  def get(key: Any): Option[Future[V]] = Option(store.getIfPresent(key)).map(_.toScala)

  def apply(key: Any, genValue: () ⇒ Future[V]): Future[V] = store.get(key, toJavaMappingFunction(genValue)).toScala

  def remove(key: Any): Unit = store.synchronous().invalidate(key)

  def clear(): Unit = store.synchronous().invalidateAll()

  def keys: Set[Any] = store.synchronous().asMap().keySet().asScala.toSet

  def size: Int = store.synchronous().asMap().size()
}
