/*
 * Copyright (C) 2020-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.settings

import akka.testkit.AkkaSpec

class ClientConnectionSettingsSpec extends AkkaSpec {
  "ClientConnectionSettings" should {
    "fail early when creating ClientConnectionSettings with incomplete ParserSettings" in {
      // This creates 'generic' parserSettings, without client-specific (or server-specific) values.
      val parserSettings = ParserSettings(system)

      // This includes ParserSettings, complete with client-specific settings
      val clientConnectionSettings = ClientConnectionSettings(system)

      // This would create 'defective' clientConnectionSettings, since these
      // parserSettings don't contain the client or server-specific values. Notably,
      // max-content-length would not be set (and throw an exception on access).
      val e = intercept[IllegalArgumentException] {
        clientConnectionSettings.withParserSettings(parserSettings)
      }
      e.getMessage should include("does not contain the client-specific settings")
    }
  }
}
