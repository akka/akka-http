/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.scaladsl

import akka.testkit.AkkaSpec

class UnmarshalSpec extends AkkaSpec {

  "use unmarshal" in {
    //#use-unmarshal
    import akka.http.scaladsl.unmarshalling.Unmarshal
    import system.dispatcher // Optional ExecutionContext (default from Materializer)

    import scala.concurrent.Await
    import scala.concurrent.duration._

    val intFuture = Unmarshal("42").to[Int]
    val int = Await.result(intFuture, 1.second) // don't block in non-test code!
    int shouldEqual 42

    val boolFuture = Unmarshal("off").to[Boolean]
    val bool = Await.result(boolFuture, 1.second) // don't block in non-test code!
    bool shouldBe false
    //#use-unmarshal
  }

  "use unmarshal without execution context" in {
    import akka.http.scaladsl.unmarshalling.Unmarshal

    import scala.concurrent.Await
    import scala.concurrent.duration._

    val intFuture = Unmarshal("42").to[Int]
    val int = Await.result(intFuture, 1.second) // don't block in non-test code!
    int shouldEqual 42

    val boolFuture = Unmarshal("off").to[Boolean]
    val bool = Await.result(boolFuture, 1.second) // don't block in non-test code!
    bool shouldBe false
  }
}
