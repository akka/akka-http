/*
 * Copyright (C) 2017-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl

import akka.event.Logging
import akka.http.impl.util.AkkaSpecWithMaterializer
import akka.testkit.{ EventFilter, TestProbe }

class SslConfigWarningsSpec extends AkkaSpecWithMaterializer(
  """
    akka.http.server.request-timeout = infinite

    akka.ssl-config {
      loose {
        disableSNI = true
        disableHostnameVerification = true
      }
    }
  """) {

  "ssl-config loose options should cause warnings to be logged" should {

    "warn if SNI is disabled globally and if hostname verification is disabled globally" in {
      val p = TestProbe()
      system.eventStream.subscribe(p.ref, classOf[Logging.LogEvent])

      EventFilter.warning(start = "Detected that Server Name Indication (SNI) is disabled globally ", occurrences = 1) intercept {
        EventFilter.warning(start = "Detected that Hostname Verification is disabled globally ", occurrences = 1) intercept {
          Http()(system)
        }
      }

      // the very big warning shall be logged only once per actor system (extension)
      EventFilter.warning(start = "Detected that Server Name Indication (SNI) is disabled globally ", occurrences = 0) intercept {
        EventFilter.warning(start = "Detected that Hostname Verification is disabled globally ", occurrences = 0) intercept {
          Http()(system)
        }
      }
    }
  }
}
