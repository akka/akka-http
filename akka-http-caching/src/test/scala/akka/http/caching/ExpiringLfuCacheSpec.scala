/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.caching

import java.util.Random
import java.util.concurrent.CountDownLatch

import akka.actor.ActorSystem
import akka.http.caching.scaladsl.CachingSettings
import akka.testkit.TestKit
import org.scalatest.BeforeAndAfterAll

import scala.concurrent.duration._
import scala.concurrent.{ Await, Future, Promise }
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ExpiringLfuCacheSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {
  implicit val system: ActorSystem = ActorSystem()
  import system.dispatcher

  "An LfuCache" should {
    "be initially empty" in {
      val cache = lfuCache[String]()
      cache.store.synchronous.asMap().size should be(0)
      cache.size should be(0)
      cache.keys should be(Set())
    }
    "store uncached values" in {
      val cache = lfuCache[String]()
      Await.result(cache.get(1, () => "A"), 3.seconds) should be("A")
      cache.size should be(1)
      cache.keys should be(Set(1))
    }
    "load uncached values with a given function" in {
      val cache = lfuCache[Int]()
      Await.result(cache.getOrLoad(1, key => Future.successful(key * 2)), 3.seconds) should be(2)
      cache.size should be(1)
      cache.keys should be(Set(1))
    }
    "return stored values upon cache hit on existing values" in {
      val cache = lfuCache[String]()
      Await.result(cache.get(1, () => "A"), 3.seconds) should be("A")
      cache.size should be(1)
    }
    "return Futures on uncached values during evaluation and replace these with the value afterwards" in {
      val cache = lfuCache[String]()
      val latch = new CountDownLatch(1)
      val future1 = cache(1, (promise: Promise[String]) =>
        Future {
          latch.await()
          promise.success("A")
        }
      )
      val future2 = cache.get(1, () => "")

      latch.countDown()
      Await.result(future1, 3.seconds) should be("A")
      Await.result(future2, 3.seconds) should be("A")
      cache.size should be(1)
    }
    "put given uncached future value" in {
      val cache = lfuCache[String]()
      val futureValue = "A"
      val done = cache.put(1, Future.successful(futureValue))

      Await.result(done, 3.seconds)
      Await.result(cache.get(1).get, 3.seconds) should be(futureValue)

    }
    "replace existing cache when evaluation of new value completes" in {
      val cache = lfuCache[String]()
      val latch = new CountDownLatch(1)
      val future1 = cache.get(1, () => "A")

      cache.get(1) should be(Some(future1))
      Await.result(future1, 3.seconds)
      val future2 = Future {
        latch.await()
        "B"
      }

      val putFuture = cache.put(1, future2)
      cache.get(1) should be(Some(future1))
      latch.countDown()

      Await.result(putFuture, 3.seconds)
      Await.result(cache.get(1).get, 3.seconds) should be("B")

    }
    "not remove existing cache when evaluation of new value fails" in {
      val cache = lfuCache[String]()
      val latch = new CountDownLatch(1)
      val future1 = cache.get(1, () => "A")
      val future2: Future[String] = Future.failed(new RuntimeException("Failure"))

      cache.get(1) should be(Some(future1))
      Await.result(future1, 3.seconds)
      latch.countDown()

      an[RuntimeException] shouldBe thrownBy {
        Await.result(cache.put(1, future2), 3.seconds)
      }
      cache.get(1) should be(Some(future1))
    }
    "properly limit capacity" in {
      val cache = lfuCache[String](maxCapacity = 3, initialCapacity = 1)
      Await.result(cache.get(1, () => "A"), 3.seconds) should be("A")
      Await.result(cache(2, () => Future.successful("B")), 3.seconds) should be("B")
      Await.result(cache.get(3, () => "C"), 3.seconds) should be("C")
      cache.get(4, () => "D")
      Thread.sleep(50)
      cache.size should be(3)
    }
    "not cache exceptions" in {
      val cache = lfuCache[String]()
      an[RuntimeException] shouldBe thrownBy {
        Await.result(cache(1, () => throw new RuntimeException("Naa")), 5.second)
      }
      Await.result(cache.get(1, () => "A"), 3.seconds) should be("A")
    }
    "refresh an entries expiration time on cache hit" in {
      val cache = lfuCache[String]()
      Await.result(cache.get(1, () => "A"), 3.seconds) should be("A")
      Await.result(cache.get(2, () => "B"), 3.seconds) should be("B")
      Await.result(cache.get(3, () => "C"), 3.seconds) should be("C")
      Await.result(cache.get(1, () => ""), 3.seconds) should be("A") // refresh
      cache.store.synchronous.asMap.toString should be("{1=A, 2=B, 3=C}")
    }
    "be thread-safe" in {
      val cache = lfuCache[Int](maxCapacity = 1000)
      // exercise the cache from 10 parallel "tracks" (threads)
      val views = Await.result(Future.traverse(Seq.tabulate(10)(identity)) { track =>
        Future {
          val array = Array.fill(1000)(0) // our view of the cache
          val rand = new Random(track)
          (1 to 10000) foreach { i =>
            val ix = rand.nextInt(1000) // for a random index into the cache
            val value = cache.get(ix, () => { // get (and maybe set) the cache value
              Thread.sleep(0)
              rand.nextInt(1000000) + 1
            }).value.get.get // should always be Future.successful
            if (array(ix) == 0) array(ix) = value // update our view of the cache
            else assert(array(ix) == value, "Cache view is inconsistent (track " + track + ", iteration " + i +
              ", index " + ix + ": expected " + array(ix) + " but is " + value)
          }
          array
        }
      }, 10.second)

      views.transpose.foreach { ints: Seq[Int] =>
        ints.filter(_ != 0).reduceLeft((a, b) => if (a == b) a else 0) should not be 0
      }
    }
    "be created with the same ttl and tti" in {
      lfuCache[Int](timeToLive = 5.seconds, timeToIdle = 5.seconds) shouldBe a[LfuCache[_, _]]
    }
  }

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }

  def lfuCache[T](maxCapacity: Int = 500, initialCapacity: Int = 16,
                  timeToLive: Duration = Duration.Inf, timeToIdle: Duration = Duration.Inf): LfuCache[Int, T] = {
    LfuCache[Int, T] {
      val settings = CachingSettings(system)
      settings.withLfuCacheSettings(
        settings.lfuCacheSettings
          .withMaxCapacity(maxCapacity)
          .withInitialCapacity(initialCapacity)
          .withTimeToLive(timeToLive)
          .withTimeToIdle(timeToIdle)
      )
    }.asInstanceOf[LfuCache[Int, T]]
  }

}
