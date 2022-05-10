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

  def loadFuture(key: K): CompletionStage[V] = futureToJava(load(key))
  def load(key: K): Future[V]

  def loadAllMap(keys: util.Set[K]): CompletionStage[util.Map[K, V]] = futureToJava(loadAll(keys.asScala.toSet)).thenApply(_.asJava)
  def loadAll(keys: Set[K]): Future[Map[K, V]]

}
