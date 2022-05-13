/*
 * Copyright (C) 2017-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.caching.scaladsl

import akka.annotation.{ ApiMayChange, DoNotInherit }

import java.util
import java.util.concurrent.CompletionStage
import scala.concurrent.Future
import scala.compat.java8.FutureConverters.{ toJava => futureToJava }
import scala.collection.JavaConverters._

/**
 * API MAY CHANGE
 *
 * General interface implemented by all akka-http cache implementations.
 */
@ApiMayChange
@DoNotInherit
trait LoadingCache[K, V] extends akka.http.caching.javadsl.LoadingCache[K, V] {

  /**
   * Returns either the cached value at `K` or attempts to load it on it's first read.
   * Should time t be refreshTime < t < expireTime, then returns the previous value and triggers a refresh.
   * Should time t be refreshTime <= expireTime < t, then triggers the loading function and returns that value.
   */
  def load(key: K): Future[V]
  def loadFuture(key: K): CompletionStage[V] = futureToJava(load(key))

  /**
   * Returns a Map of all values or fails.
   */
  def loadAll(keys: Set[K]): Future[Map[K, V]]
  def loadAllMap(keys: util.Set[K]): CompletionStage[util.Map[K, V]] = futureToJava(loadAll(keys.asScala.toSet)).thenApply(_.asJava)

}
