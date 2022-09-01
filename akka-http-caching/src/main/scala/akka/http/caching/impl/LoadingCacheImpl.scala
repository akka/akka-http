/*
 * Copyright (C) 2017-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.caching.impl

import akka.annotation.InternalApi
import akka.http.caching.LoadingCache.toJavaMappingFunction
import akka.http.caching.scaladsl.LoadingCache
import akka.http.impl.util.JavaMapping.Implicits.convertToScala
import com.github.benmanes.caffeine.cache.AsyncLoadingCache

import java.util
import java.util.concurrent.CompletableFuture
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.compat.java8.FutureConverters._
import scala.compat.java8.FunctionConverters._

import scala.concurrent.Future
import scala.compat.java8.FutureConverters.{ toScala => futureToScala }

/** INTERNAL API */
@InternalApi
class LoadingCacheImpl[K, V](override val store: AsyncLoadingCache[K, V]) extends CacheImpl(store) with LoadingCache[K, V] {
  override def load(key: K): Future[V] = futureToScala(store.get(key))

  override def loadAll(keys: Set[K]): Future[Map[K, V]] =
    futureToScala(
      store
        .getAll(keys.asJava)
        .thenApply(asJavaFunction((_: util.Map[K, V]).asScala.toMap))
    )

}
