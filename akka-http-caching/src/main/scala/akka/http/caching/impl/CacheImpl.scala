package akka.http.caching.impl

import akka.annotation.InternalApi
import akka.http.caching.LfuCache.toJavaMappingFunction
import akka.http.caching.scaladsl.Cache
import com.github.benmanes.caffeine.cache.AsyncCache

import scala.collection.JavaConverters._
import scala.compat.java8.FutureConverters._
import scala.concurrent.{ ExecutionContext, Future }

/** INTERNAL API */
@InternalApi
private[caching] class CacheImpl[K, V](val store: AsyncCache[K, V]) extends Cache[K, V] {

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
