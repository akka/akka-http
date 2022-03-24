/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http
package scaladsl
package model
package sse

import akka.util.ByteString
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

final class ServerSentEventSpec extends AnyWordSpec with Matchers with ScalaCheckDrivenPropertyChecks {

  "Creating a ServerSentEvent" should {
    "throw an IllegalArgumentException if type contains a \n or \r character" in {
      an[IllegalArgumentException] should be thrownBy ServerSentEvent("data", "type\n")
      an[IllegalArgumentException] should be thrownBy ServerSentEvent("data", "type\rtype")
    }

    "throw an IllegalArgumentException if id contains a \n or \r character" in {
      an[IllegalArgumentException] should be thrownBy ServerSentEvent("data", id = Some("id\n"))
      an[IllegalArgumentException] should be thrownBy ServerSentEvent("data", id = Some("id\rid"))
    }

    "throw an IllegalArgumentException if retry is not a positive number" in {
      forAll("retry") { (n: Int) =>
        whenever(n <= 0) {
          an[IllegalArgumentException] should be thrownBy ServerSentEvent("data", n)
        }
      }
    }
  }

  "A ServerSentEvent heartbeat" should {
    "be an empty ServerSentEvent" in {
      val event = ServerSentEvent.heartbeat
      event shouldBe ServerSentEvent("")
    }
  }

  "Calling encode" should {
    "return a single data line" in {
      val event = ServerSentEvent(" ")
      event.encode shouldBe ByteString.fromString("data: \n\n")
    }

    "return multiple data lines" in {
      val event = ServerSentEvent("data1\ndata2\n")
      event.encode shouldBe ByteString.fromString("data:data1\ndata:data2\ndata:\n\n")
    }

    "return data lines and an event line" in {
      val event = ServerSentEvent("data1\ndata2", "type")
      event.encode shouldBe ByteString.fromString("data:data1\ndata:data2\nevent:type\n\n")
    }

    "return a data line and an id line" in {
      val event = ServerSentEvent("data", id = Some("id"))
      event.encode shouldBe ByteString.fromString("data:data\nid:id\n\n")
    }

    "return a data line and a retry line" in {
      val event = ServerSentEvent("data", 42)
      event.encode shouldBe ByteString.fromString("data:data\nretry:42\n\n")
    }

    "return all possible lines" in {
      val event = ServerSentEvent("data", Some("type"), Some("id"), Some(42))
      event.encode shouldBe ByteString.fromString("data:data\nevent:type\nid:id\nretry:42\n\n")
    }

    "not return and an event line for an empty type" in {
      val event = ServerSentEvent("data1\ndata2", "")
      event.encode shouldBe ByteString.fromString("data:data1\ndata:data2\n\n")
    }
  }
}
