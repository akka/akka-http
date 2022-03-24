/*
 * Copyright (C) 2020-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.settings

import akka.testkit.AkkaSpec

import scala.concurrent.duration._

class Http2CommonSettingsSpec extends AkkaSpec {

  "Validation of HTTP2 settings" should {

    "require ping-timeout to be evenly divisable by ping-interval" in {
      import Http2CommonSettings.validate
      val default = Http2ClientSettings(system)
      validate(default) // default is disabled, should be ok
      validate(default.withPingInterval(4.seconds)) // undefined timeout means same as interval
      validate(default.withPingTimeout(2.seconds)) // if ping not enabled (interval 0) it does not matter
      validate(default.withPingInterval(4.seconds).withPingTimeout(0.seconds)) // undefined timeout means same as interval
      validate(default.withPingInterval(4.seconds).withPingTimeout(2.seconds)) // evenly divisible is ok
      intercept[IllegalArgumentException] {
        validate(default.withPingInterval(4.seconds).withPingTimeout(502.millis)) // not evenly divisible should throw
      }
      intercept[IllegalArgumentException] {
        validate(default.withPingInterval(4.seconds).withPingTimeout(5.seconds)) // larger than interval should throw
      }
    }

  }

}
