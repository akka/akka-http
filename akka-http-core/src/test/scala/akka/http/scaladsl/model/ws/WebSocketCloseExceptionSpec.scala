/*
 * Copyright (C) 2009-2025 Lightbend Inc. <https://akka.io>
 */

package akka.http.scaladsl.model.ws

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class WebSocketCloseExceptionSpec extends AnyWordSpec with Matchers {
  "WebSocketCloseException" should {
    "accept application close codes" in {
      new WebSocketCloseException(3000).closeCode shouldEqual 3000
      new WebSocketCloseException(3000).closeReason shouldEqual ""
      val e = new WebSocketCloseException(4999, "x" * 123)
      e.closeCode shouldEqual 4999
      e.closeReason shouldEqual "x" * 123
    }
    "reject close codes outside of 3000-4999" in {
      Seq(1000, 1011, 2999, 5000).foreach { code =>
        an[IllegalArgumentException] should be thrownBy new WebSocketCloseException(code, "reason")
      }
    }
    "reject a close reason longer than 123 bytes" in {
      an[IllegalArgumentException] should be thrownBy new WebSocketCloseException(4000, "x" * 124)
      // 62 * 2 bytes when UTF-8 encoded
      an[IllegalArgumentException] should be thrownBy new WebSocketCloseException(4000, "ä" * 62)
    }
  }
}
