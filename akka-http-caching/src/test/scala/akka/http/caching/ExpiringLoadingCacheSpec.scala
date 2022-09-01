/*
 * Copyright (C) 2017-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.caching

import java.util.concurrent.Executor
import akka.actor.ActorSystem
import akka.http.caching.scaladsl.CachingSettings
import akka.testkit.TestKit
import com.github.benmanes.caffeine.cache.AsyncCacheLoader
import org.scalatest.BeforeAndAfterAll

import scala.compat.java8.FutureConverters.{ toJava => toJavaFuture }
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ExpiringLoadingCacheSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {
  implicit val system: ActorSystem = ActorSystem()
  import system.dispatcher

  "A RefreshingCache" should {
    "refresh values after the refresh-after-write" in {
      val wait = 1.second

      def loader: AsyncCacheLoader[Int, String] = (_: Int, _: Executor) => toJavaFuture(Future.successful("B")).toCompletableFuture

      val cache = refreshingCache[String](refreshAfterWrite = wait, cacheLoader = loader)
      cache.put(1, Future.successful("A"))

      Await.result(cache.load(1), wait) shouldBe "A"
      Await.result(akka.pattern.after(wait, system.scheduler)(cache.load(1)), wait * 3) shouldBe "A"
      Await.result(akka.pattern.after(1.microsecond, system.scheduler)(cache.load(1)), wait) shouldBe "B"
    }

    "evicts entries values after expire-after-write" in {
      val wait = 1.second

      def loader: AsyncCacheLoader[Int, String] = (_: Int, _: Executor) =>
        toJavaFuture[String](Future.failed(new NoSuchElementException("404: ¯\\_(ツ)_/¯"))).toCompletableFuture

      val cache = refreshingCache[String](refreshAfterWrite = wait, expireAfterWrite = wait / 2, cacheLoader = loader)
      cache.put(1, Future.successful("A"))

      Await.result(cache.load(1), wait) shouldBe "A"

      a[NoSuchElementException] shouldBe thrownBy {
        Await.result(akka.pattern.after(wait, system.scheduler)(cache.load(1)), wait * 2)
      }
    }

    "loads multiple values" in {
      val wait = 1.second

      def loader: AsyncCacheLoader[Int, String] = (t: Int, _: Executor) =>
        toJavaFuture(Future.successful(t.toString)).toCompletableFuture

      val cache = refreshingCache[String](refreshAfterWrite = wait, cacheLoader = loader)

      Await.result(cache.loadAll(Set(1, 2, 3)), wait) shouldBe Map(
        1 -> "1",
        2 -> "2",
        3 -> "3"
      )
    }

    "fail when loader fails" in {
      val wait = 1.second

      def loader: AsyncCacheLoader[Int, String] = (_: Int, _: Executor) =>
        toJavaFuture[String](Future.failed(new NoSuchElementException("404: ¯\\_(ツ)_/¯"))).toCompletableFuture

      val cache = refreshingCache[String](refreshAfterWrite = wait, cacheLoader = loader)

      a[NoSuchElementException] shouldBe thrownBy {
        Await.result(cache.load(1), wait)
      }
    }
  }

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }

  def dummyLoader[K, V]: AsyncCacheLoader[K, V] = (_: K, _: Executor) => toJavaFuture[V](Future.failed(new NoSuchElementException(""))).toCompletableFuture

  def refreshingCache[T](
    maxCapacity:       Int                      = 500,
    refreshAfterWrite: Duration                 = 1.second,
    expireAfterWrite:  Duration                 = Duration.Inf,
    cacheLoader:       AsyncCacheLoader[Int, T] = dummyLoader[Int, T]): LoadingCache[Int, T] = {
    LoadingCache[Int, T]({
      val settings = CachingSettings(system)
      settings.withRefreshingCacheSettings(
        settings.refreshingCacheSettings
          .withMaxCapacity(maxCapacity)
          .withRefreshAfterWrite(refreshAfterWrite)
          .withExpireAfterWrite(expireAfterWrite)
      )
    }, cacheLoader)
  }

}
