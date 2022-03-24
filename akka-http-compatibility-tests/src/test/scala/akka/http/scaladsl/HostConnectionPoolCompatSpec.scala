/*
 * Copyright (C) 2020-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl

import akka.http.impl.util.AkkaSpecWithMaterializer
import akka.stream.scaladsl.{ Keep, Sink, Source }
import example.HostConnectionPoolCompat

class HostConnectionPoolCompatSpec extends AkkaSpecWithMaterializer {
  "HostConnectionPool" should {
    "be compatible" in {
      val hcp0 =
        Source.empty
          .viaMat(Http().cachedHostConnectionPool("localhost", 8080))(Keep.right)
          .to(Sink.ignore)
          .run()

      val hcp1 =
        Source.empty
          .viaMat(Http().cachedHostConnectionPool("localhost", 8080))(Keep.right)
          .to(Sink.ignore)
          .run()

      val hcpOther =
        Source.empty
          .viaMat(Http().newHostConnectionPool("localhost", 8080))(Keep.right)
          .to(Sink.ignore)
          .run()

      hcp0 shouldEqual hcp1
      hcp0 should not equal (hcpOther)

      HostConnectionPoolCompat.access(hcp0)
    }
  }
}
