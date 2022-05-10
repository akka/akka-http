package akka.http.caching.impl

import akka.annotation.InternalApi
import akka.http.caching.scaladsl.LoadingCache
import com.github.benmanes.caffeine.cache.AsyncLoadingCache

import scala.concurrent.Future
import scala.compat.java8.FutureConverters._
import scala.collection.JavaConverters._
import scala.compat.java8.FutureConverters.{ toJava => futureToJava, toScala => futureToScala }

/** INTERNAL API */
@InternalApi
class LoadingCacheImpl[K, V](override val store: AsyncLoadingCache[K, V]) extends CacheImpl(store) with LoadingCache[K, V] {
  override def load(key: K): Future[V] = futureToScala(store.get(key))

  override def loadAll(keys: Set[K]): Future[Map[K, V]] = store.getAll(keys.asJava).toCompletableFuture.thenApply(_.asScala.toMap).toScala
}
