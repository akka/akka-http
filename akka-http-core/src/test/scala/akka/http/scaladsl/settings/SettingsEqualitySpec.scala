/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.settings

import com.typesafe.config.ConfigFactory

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class SettingsEqualitySpec extends AnyWordSpec with Matchers {

  val config = ConfigFactory.load.resolve

  "equality" should {
    "hold for ConnectionPoolSettings" in {
      val s1 = ConnectionPoolSettings(config)
      val s2 = ConnectionPoolSettings(config)

      s1 shouldBe s2
      s1.toString should startWith("ConnectionPoolSettings(")
    }

    "hold for ParserSettings" in {
      val s1 = ParserSettings(config)
      val s2 = ParserSettings(config)

      s1 shouldBe s2
      s1.toString should startWith("ParserSettings(")
    }

    "hold for ClientConnectionSettings" in {
      val s1 = ClientConnectionSettings(config)
      val s2 = ClientConnectionSettings(config)

      s1 shouldBe s2
      s1.toString should startWith("ClientConnectionSettings(")
    }

    "hold for ServerSettings" in {
      val s1 = ServerSettings(config)
      val s2 = ServerSettings(config)

      s1 shouldBe s2
      s1.toString should startWith("ServerSettings(")
    }
  }

}
