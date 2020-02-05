/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.settings

import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class RoutingSettingsEqualitySpec extends AnyWordSpec with Matchers {

  val config = ConfigFactory.load.resolve

  "equality" should {

    "hold for RoutingSettings" in {
      val s1 = RoutingSettings(config)
      val s2 = RoutingSettings(config)

      s1 shouldBe s2
      s1.toString should startWith("RoutingSettings(")
    }

  }

}
