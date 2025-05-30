/*
 * Copyright (C) 2009-2025 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.http.impl.settings

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

class CorsSettingsSpec extends AnyWordSpec with Matchers {

  "The CORS settings" should {

    "do case insensitive header name matching" in {
      val settings = new CorsSettingsImpl(true, true, Set.empty, Set("Content-Type"), Set.empty, Set.empty, 10.minutes)
      settings.headerNameAllowed("Content-Type") shouldBe true
      settings.headerNameAllowed("content-type") shouldBe true
      settings.headerNameAllowed("CONTENT-TYPE") shouldBe true
    }

  }

}
